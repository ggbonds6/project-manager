package com.pmgt.ai.common.config;

import com.pmgt.ai.common.model.ModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动自检：把"配置错了但服务照样起得来"的情况**在启动日志里喊出来**。
 *
 * <p>为什么需要它（真实踩过）：`.env` 是<b>相对进程工作目录</b>解析的。从仓库根启动 jar 时
 * `./.env` 会落空，而 Spring 的 optional 导入不会报错——结果是服务正常启动、`/health` 也返回 200，
 * 直到第一次调平台才抛出 `HTTP 401 missing api key`，排查时容易怀疑是平台或密钥本身的问题。
 *
 * <p>2026-10-10（P0-1/P0-5/P0-6）新增两件事：
 * <ol>
 *   <li><b>超时链不变式校验</b>：所有模型超时必须 &lt; 调用方（主系统→AI，180s）预算。
 *       违反时打 ERROR（不直接退出进程：退出会让"配置写错"变成"服务起不来"，
 *       而评估 §5.1 的 fail-fast 属 P1 范围；这里先做到"启动就喊出来"）；</li>
 *   <li><b>四模型超时/并发一览</b>：一眼能核对"chat 150 / embed 30 / rerank 20 / ocr 600"
 *       与"chat 4 / ocr 12 / embed 8 / rerank 2"，不用去翻 yml。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDiagnostics implements ApplicationRunner {

    private final AiSettings settings;
    private final ModelRegistry modelRegistry;

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(settings.getGateway().getApiKey())) {
            log.error("""
                    未配置平台网关密钥（ai.gateway.api-key / 环境变量 LLM_API_KEY）：
                    OCR、抽取、问答、向量化、重排都会返回 401 missing api key。
                    处理：① 本机开发把凭据放进 ai-backend/.env（或从 ai-backend 目录启动）；
                          ② 容器/服务器用环境变量或 .env 注入。""");
        } else {
            log.info("平台网关 {}（密钥已配置，对话/OCR/向量化/重排共用）",
                    settings.getGateway().getBaseUrl());
        }

        String backend = settings.getRetrieval().getBackend();
        if (!"local".equals(backend) && isBlank(settings.getOpenSearch().getUrl())) {
            log.error("VEC_BACKEND={} 但 OPENSEARCH_URL 为空：检索会**显式报错**（不静默退回进程内检索）。", backend);
        } else if ("opensearch".equalsIgnoreCase(backend)) {
            log.info("检索后端 opensearch：{}（索引 {}；单节点请确认 number_of_shards=1 / number_of_replicas=0；"
                            + "两条 URL 是两个独立单节点集群，不要同时接业务）",
                    settings.getOpenSearch().getUrl(), settings.getOpenSearch().getIndex());
        } else {
            log.info("检索后端 {}（local=进程内余弦；opensearch 需 OPENSEARCH_URL 可达）", backend);
        }

        checkTimeoutChain();
        logModelTable();
        checkRecallMode();
    }

    /**
     * 超时链不变式：<b>交互式模型（chat / embed / rerank）的超时 &lt; 调用方预算（180s）</b>。
     *
     * <p>违反的后果正是 P0-1 要修的："用户（180s）已经放弃，AI 侧还在跑，槽位被白占"。
     *
     * <p><b>OCR 是有意的例外</b>：它跑在后台解析任务里（{@code /upload-tasks} 先返回、再解析），
     * 没有任何调用方在为这一次请求倒计时，而单份扫描件的 OCR 本来就可能跑几分钟
     * （600s = 单页 ~1.8s × 16 页 + 排队余量）。所以这里只对它打一条 INFO 说明，
     * 不按交互式口径判违规——但把"为什么"写进日志，免得下一个人以为漏了校验。
     */
    private void checkTimeoutChain() {
        double caller = settings.getLimits().getCallerTimeoutSeconds();
        for (ModelRegistry.Kind kind : List.of(ModelRegistry.Kind.CHAT, ModelRegistry.Kind.EMBED,
                ModelRegistry.Kind.RERANK)) {
            double timeout = modelRegistry.timeoutSeconds(kind);
            if (timeout >= caller) {
                log.error("超时链违反不变式：模型 {} 超时 {}s ≥ 调用方预算 {}s（AI_CHAT_TIMEOUT）。"
                                + "后果：用户已超时、槽位仍被占用。请把 {}-timeout 调到 < {}s。",
                        kind.key(), timeout, caller, kind.key(), caller);
            }
        }
        double ocr = modelRegistry.timeoutSeconds(ModelRegistry.Kind.OCR);
        log.info("超时链：调用方（主系统→AI）{}s > chat {}s / rerank {}s / embed {}s；"
                        + "OCR {}s 是**有意的例外**（后台解析任务，没有调用方在倒计时）。"
                        + "chat 侧最坏占用从 300s（旧默认）降到 {}s",
                caller, modelRegistry.timeoutSeconds(ModelRegistry.Kind.CHAT),
                modelRegistry.timeoutSeconds(ModelRegistry.Kind.RERANK),
                modelRegistry.timeoutSeconds(ModelRegistry.Kind.EMBED), ocr,
                modelRegistry.timeoutSeconds(ModelRegistry.Kind.CHAT));
    }

    /** 四模型的"超时 / 在飞上限 / 是否允许重试"一览（取值依据见 AiSettings.Limits 的注释）。 */
    private void logModelTable() {
        for (var item : modelRegistry.configSummary()) {
            log.info("模型 {}：{}｜超时 {}s｜并发 {}｜幂等（可重试）={}",
                    item.get("model"), item.get("name"), item.get("timeout_seconds"),
                    item.get("concurrency"), item.get("idempotent"));
        }
        log.info("幂等重试：最多 {} 次，退避 {}ms ×{} + 抖动 {}ms；chat 永不重试",
                settings.getLimits().getRetryMax(), settings.getLimits().getRetryBackoffMs(),
                settings.getLimits().getRetryBackoffMultiplier(), settings.getLimits().getRetryJitterMs());
    }

    /** 召回档位的自检（P0-7：默认必须是 baseline，降配必须显式且被记录）。 */
    private void checkRecallMode() {
        String mode = settings.getRetrieval().getRecallMode();
        if ("low".equalsIgnoreCase(mode == null ? "" : mode.strip())) {
            log.warn("召回已降配：RETRIEVAL_RECALL_MODE=low → recall={}（baseline 是 {}）。"
                            + "改默认前必须用 eval/smoke-30.json 跑前后对比（引用命中率/数字一致率不降才可改）。",
                    settings.getRetrieval().getRecallLow(), settings.getRetrieval().getRecall());
        } else {
            log.info("召回档位 {}：recall={}（一次问答的重排对数 ≈ 3 × recall；"
                            + "降到 20~25 可把问答上限从 ≈0.16 QPS 提到 ≈0.4 QPS，但需先评测）",
                    mode, settings.getRetrieval().getRecall());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

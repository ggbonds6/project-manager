package com.pmgt.ai.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：把"配置错了但服务照样起得来"的情况**在启动日志里喊出来**。
 *
 * <p>为什么需要它（真实踩过）：`.env` 是**相对进程工作目录**解析的。从仓库根启动 jar 时
 * `./.env` 会落空，而 Spring 的 optional 导入不会报错——结果是服务正常启动、`/health` 也返回 200，
 * 直到第一次调平台才抛出 `HTTP 401 missing api key`，排查时容易怀疑是平台或密钥本身的问题。
 * 这里在启动阶段就把缺密钥、以及检索后端配置状态打到日志里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupDiagnostics implements ApplicationRunner {

    private final AiSettings settings;

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
        } else {
            log.info("检索后端 {}（local=进程内余弦；opensearch 需 OPENSEARCH_URL 可达）", backend);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

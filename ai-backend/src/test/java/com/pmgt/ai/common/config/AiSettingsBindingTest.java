package com.pmgt.ai.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code application.yml} 的键必须真的绑到 {@link AiSettings} 上（三处一致核对：yml / .env.example / README）。
 *
 * <p>为什么值得一条用例：Spring 的 {@code @ConfigurationProperties} 对<b>不认识的键是静默忽略</b>的
 * （拼错一个 {@code batch-parse} 就是"配置写了但没生效"，而服务照常启动、日志也没有任何异常）。
 * 本用例把 yml 真读进来绑一遍，键名写错会直接红。
 */
class AiSettingsBindingTest {

    private static AiSettings bound() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application",
                new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addFirst(source);
        }
        // yml 里大量使用 ${ENV:默认} 占位符，绑定前必须解析它们（否则 bind 到第一个占位符就失败）
        return Binder.get(environment).bind("ai", AiSettings.class).orElseGet(AiSettings::new);
    }

    @Test
    @DisplayName("ai.gate.* 全部绑上：五条通道的上限/队列/等待/每项目上限与老化参数")
    void gateBindsAllChannels() throws Exception {
        AiSettings settings = bound();
        AiSettings.Gate gate = settings.getGate();

        assertTrue(gate.isEnabled(), "默认启用 L1");
        assertEquals(4, gate.getInteractive().getMax());
        assertEquals(0, gate.getInteractive().getQueue(), "交互式队列默认 0（快速失败的前提）");
        assertEquals(1500, gate.getInteractive().getWaitMs());

        assertEquals(4, gate.getBatchParse().getMax());
        assertEquals(100, gate.getBatchParse().getQueue());
        assertEquals(0, gate.getBatchParse().getWaitMs());
        assertEquals(4, gate.getBatchParse().getPerProjectMax());

        assertEquals(1, gate.getBatchReindex().getMax());
        assertEquals(2, gate.getBatchReindex().getQueue());
        assertEquals(1, gate.getBatchReindex().getPerProjectMax());

        assertEquals(4, gate.getBatchEmbed().getMax());
        assertEquals(100, gate.getBatchEmbed().getQueue());
        assertEquals(60000, gate.getBatchEmbed().getWaitMs());

        assertEquals(1, gate.getBatchEval().getMax());

        assertEquals(4, gate.getPerProjectInflightMax());
        assertTrue(gate.isPauseBatchWhenInteractive(), "软闸门默认开");
        assertEquals(100, gate.getPriority().getInteractiveWeight());
        assertEquals(20, gate.getPriority().getBatchParseWeight());
        assertEquals(10, gate.getPriority().getBatchReindexWeight());
        assertEquals(5, gate.getPriority().getBatchEmbedWeight());
        assertEquals(0, gate.getPriority().getBatchEvalWeight());
        assertEquals(30, gate.getPriority().getAgingStepSeconds());
        assertEquals(2, gate.getPriority().getAgingBonusPerStep());
        assertEquals(40, gate.getPriority().getAgingBonusMax());
    }

    @Test
    @DisplayName("ai.pipeline.* 全部绑上：开关/地址/令牌/超时/重试/心跳/切片键形态与三个 rev")
    void pipelineBindsAllKeys() throws Exception {
        AiSettings settings = bound();
        AiSettings.Pipeline pipeline = settings.getPipeline();

        assertFalse(pipeline.isEnabled(), "默认不启用上报（没配地址/令牌时不做任何外呼）");
        assertEquals("", pipeline.getServiceToken(), "令牌默认空（绝不写进仓库）");
        assertEquals("", pipeline.getBaseUrl());
        assertEquals(3.0, pipeline.getTimeoutSeconds());
        assertEquals(0, pipeline.getMaxRetries(), "非终态上报不重试");
        assertEquals(2, pipeline.getFinishRetries(), "终态要重试");
        assertEquals(200, pipeline.getRetryBackoffMs());
        assertEquals(30, pipeline.getHeartbeatSeconds());
        assertEquals(90, pipeline.getLeaseSeconds());
        assertTrue(pipeline.isMdc());
        assertEquals("legacy", pipeline.getChunkKeyFormat(), "默认 legacy：升级不使既有索引/缓存失效");
        assertEquals(1, pipeline.getParseRev());
        assertEquals(0, pipeline.getCleanRev(), "0 = 本轮尚无清洗阶段");
        assertEquals(0, pipeline.getChunkRev());
    }

    @Test
    @DisplayName("ai.clean.* / ai.chunk.* 全部绑上：清洗开关与规则清单、重叠比例、max_char_len、父子块摘要")
    void cleanAndChunkBindAllKeys() throws Exception {
        AiSettings settings = bound();
        AiSettings.Clean clean = settings.getClean();
        assertFalse(clean.isEnabled(), "清洗默认关（行为与接入前逐字一致）");
        assertEquals("c1,c2,c3,c4,c5,c6,c7", clean.getRules(), "默认规则清单 = C1~C7（C8 水印默认关）");
        assertEquals(0.5, clean.getHeaderFooterRatio());
        assertEquals(4, clean.getHeaderFooterMinChars());
        assertFalse(clean.isDedupDrop(), "段级去重默认只标记不删");
        assertEquals("", clean.getWatermarkPatterns());

        AiSettings.Chunk chunk = settings.getChunk();
        assertFalse(chunk.isImproved(),
                "改进切分默认关（与接入前切法逐字一致；开启需先有 citation_pages 标注与 recall@5 基线）");
        assertEquals(500, chunk.getChunkChars());
        assertEquals(2000, chunk.getMaxCharLen(), "硬上限 2000（验收：不存在 >2000 字符的切片）");
        assertEquals(0.12, chunk.getOverlapRatio(), "重叠比例默认 0.12（设计给的 10%~15% 区间内）");
        assertTrue(chunk.isStructureAware());
        assertTrue(chunk.isTablesWhole());
        assertEquals(200, chunk.getParentSummaryChars());
    }

    @Test
    @DisplayName("既有的 ai.limits / ai.retrieval 键没被新配置改动（回归：三处一致的另一半）")
    void existingKeysUnchanged() throws Exception {
        AiSettings settings = bound();
        assertEquals(1500, settings.getLimits().getInteractiveAcquireMs());
        assertEquals(60000, settings.getLimits().getBatchAcquireMs());
        assertEquals(4, settings.getLimits().getChatConcurrency());
        assertEquals(8, settings.getLimits().getEmbedConcurrency());
        assertEquals(2, settings.getLimits().getRerankConcurrency());
        assertEquals(12, settings.getOcr().getConcurrency());
        assertEquals("local", settings.getRetrieval().getBackend());
        assertEquals(50, settings.getRetrieval().getRecall());
    }
}

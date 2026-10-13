package com.pmgt.ai.module.llm;

import com.pmgt.ai.testutil.TstRegistry;
import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import com.pmgt.ai.testutil.StubHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修掉 {@code LlmClient} 不使用传入 {@code timeoutSeconds} 的缺陷（P0-1）。
 *
 * <p>改造前：超时一律取 {@code gateway.timeout-seconds}（300s），于是
 * {@code ping(15.0)} 自称 15s、实际最长卡 300s；而 AI 侧 300s &gt; 调用方 180s，
 * "用户已放弃、槽位仍被占用"。
 *
 * <p>本测试用真 HTTP 桩（本机随机端口）把"超时到底是多少"变成可观测事实：
 * ① 直接断言优先级函数；② 用 1s 超时打一个 1.5s 才回复的桩，必须在 1s 左右失败——
 * 若实现仍取 300s，这条会挂 1.5s+ 且不会失败。
 */
class LlmClientTimeoutTest {

    private static AiSettings settings(String baseUrl, double chatTimeout) {
        AiSettings settings = new AiSettings();
        settings.getGateway().setBaseUrl(baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1");
        settings.getGateway().setApiKey("sk-test-not-a-real-key");
        settings.getLimits().setChatTimeoutSeconds(chatTimeout);
        return settings;
    }

    private static String chatResponse() {
        return "{\"model\":\"Qwen3.8-27B-W8A8\",\"choices\":[{\"message\":{\"content\":\"正常\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}";
    }

    @Test
    @DisplayName("超时优先级：传入值 > 每模型超时（chat 150s）> 网关兜底值")
    void timeoutPrecedence() {
        AiSettings settings = new AiSettings();
        LlmClient client = new LlmClient(settings, TstRegistry.l2(settings));

        assertEquals(150_000L, client.effectiveTimeoutMillis(null), "不传就用每模型超时（chat 150s）");
        assertEquals(15_000L, client.effectiveTimeoutMillis(15.0), "ping(15) 必须真的用 15s");
        assertEquals(1_000L, client.effectiveTimeoutMillis(1.0));

        settings.getLimits().setChatTimeoutSeconds(0);      // 关掉每模型超时
        settings.getGateway().setTimeoutSeconds(300);
        assertEquals(300_000L, client.effectiveTimeoutMillis(null), "没有分级配置时才回落到网关值");
    }

    @Test
    @DisplayName("1s 超时打 1.5s 才回复的网关：~1s 就失败（证明传入值真的生效，而不是回到 300s）")
    void passedTimeoutIsActuallyApplied() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            Thread.sleep(1500);
            return chatResponse();
        })) {
            AiSettings settings = settings(server.baseUrl(), 0);
            settings.getGateway().setTimeoutSeconds(1);      // 1s
            LlmClient client = new LlmClient(settings, TstRegistry.l2(settings));

            long started = System.nanoTime();
            LlmClient.LlmException error = assertThrows(LlmClient.LlmException.class,
                    () -> client.chat("你好", "system"));
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

            assertTrue(seconds < 1.4, "必须在 1s 左右失败（实际 " + seconds + "s）——否则说明超时没生效");
            assertTrue(error.getMessage().contains("Timeout") || error.getMessage().contains("timed out"),
                    error.getMessage());
        }
    }

    @Test
    @DisplayName("ping(1.0)：自检用**自己**的短超时，网关挂死时 ~1s 返回 ok=false")
    void pingUsesItsOwnShortTimeout() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> {
            Thread.sleep(1500);
            return chatResponse();
        })) {
            AiSettings settings = settings(server.baseUrl(), 150);   // 每模型超时是 150s
            LlmClient client = new LlmClient(settings, TstRegistry.l2(settings));

            long started = System.nanoTime();
            LlmClient.LlmPing ping = client.ping(1.0);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;

            assertFalse(ping.ok());
            assertTrue(seconds < 1.4, "ping(1.0) 必须在 ~1s 返回（实际 " + seconds + "s），不能等 150s/300s");
        }
    }

    @Test
    @DisplayName("正常路径：网关 200 且很快返回时照常拿到内容（超时参数不影响成功调用）")
    void successfulCallStillWorks() throws Exception {
        try (StubHttpServer server = new StubHttpServer((method, path, body) -> chatResponse())) {
            AiSettings settings = settings(server.baseUrl(), 150);
            LlmClient client = new LlmClient(settings, TstRegistry.l2(settings));

            LlmClient.ChatResult result = client.chat("你好", "system");

            assertEquals("正常", result.text());
            assertEquals("Qwen3.8-27B-W8A8", result.model());
            assertTrue(server.lastRequest().contains("/v1/chat/completions"), server.lastRequest());
        }
    }
}

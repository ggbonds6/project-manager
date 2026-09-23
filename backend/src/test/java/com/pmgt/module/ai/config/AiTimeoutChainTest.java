package com.pmgt.module.ai.config;

import com.pmgt.module.ai.query.ScopeTokenService;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「问答超时链」不变式测试。
 *
 * <pre>
 *   前端问答超时 180s ｜ nginx proxy_read_timeout 300s ｜ 主系统→AI 180s ｜ scope_token 300s
 *   不变式：scope_token ≥ 主系统→AI ≥ 前端，且【四者都不超过 nginx】
 * </pre>
 *
 * <h2>为什么值得单独一条测试</h2>
 * <p>这四个数字住在四个不同的文件里（前端、nginx、compose/.env、Java/yml），任何一处漂移都会
 * 表现为"线上偶发超时"，而排障时会先怀疑模型慢、再怀疑网络——很难想到是某个文件里的默认值。
 * 本测试把不变式钉死：<b>改小 token / 改大主系统超时到超过 nginx，都会立刻变红</b>。
 *
 * <p>为什么"不能超过 nginx"这么严：超过时请求会先被网关掐成 504，用户看到的是"网关错误"
 * 而不是主系统给出的「AI 服务不可用」，排障方向直接跑偏（这是本项目的既有约定：
 * 错误必须能指向真正的故障点）。
 */
class AiTimeoutChainTest {

    /** 前端问答超时（秒）：前端已放宽到 180。 */
    private static final int FRONTEND_SECONDS = 180;

    /** nginx proxy_read_timeout（秒），见 deploy/docker/frontend-nginx.conf。 */
    private static final int NGINX_SECONDS = 300;

    @Test
    void Java默认值与前端对齐为180() {
        // 没有配置文件时的兜底值也必须对齐：否则有人只改 yml 会以为改完了
        assertEquals(180, new AiProperties().getChatTimeoutSeconds(),
                "pm.ai.chat-timeout-seconds 的 Java 默认值必须与前端 180s 对齐");
    }

    @Test
    void applicationYml的默认值也是180与300() throws Exception {
        Map<String, Object> ai = aiSection();
        assertEquals("${AI_CHAT_TIMEOUT:180}", ai.get("chat-timeout-seconds"),
                "application.yml 的默认值必须与 Java/compose 一致（compose 没传时不再回落到 60）");
        assertEquals("${PM_AI_SCOPE_TOKEN_TTL_SECONDS:300}", ai.get("scope-token-ttl-seconds"));
        // 非问答接口的超时（健康探测/列表/轮询）必须保持"快速失败"，不要被顺手放大
        assertEquals("${AI_TIMEOUT:10}", ai.get("timeout-seconds"),
                "AI_TIMEOUT 不是问答超时，不能跟着放大到 180");
    }

    @Test
    void 部署模板三处的默认值一致() throws Exception {
        // deploy/ 不在测试 classpath 上：按约定从模块目录的相对路径读；文件不在（例如只拿了 backend 目录）就跳过
        Path root = Path.of("..", "deploy", "docker");
        assumeTrue(Files.isDirectory(root), "未找到 deploy/docker 目录，跳过部署模板一致性检查");

        String envExample = read(root.resolve(".env.example"));
        String compose = read(root.resolve("docker-compose.yml"));
        String composeDeploy = read(root.resolve("docker-compose.deploy.yml"));

        assertTrue(envExample.contains("AI_CHAT_TIMEOUT=180"), ".env.example 应为 180");
        assertTrue(compose.contains("AI_CHAT_TIMEOUT: ${AI_CHAT_TIMEOUT:-180}"), "docker-compose.yml 应为 :-180");
        assertTrue(composeDeploy.contains("AI_CHAT_TIMEOUT: ${AI_CHAT_TIMEOUT:-180}"),
                "docker-compose.deploy.yml 应为 :-180");
        // 非问答超时不要被顺手放大
        assertTrue(envExample.contains("AI_TIMEOUT=10"), ".env.example 的 AI_TIMEOUT 应保持 10");
        assertTrue(compose.contains("AI_TIMEOUT: ${AI_TIMEOUT:-10}"), compose);
        assertTrue(composeDeploy.contains("AI_TIMEOUT: ${AI_TIMEOUT:-10}"), composeDeploy);
        // 四处的 token 上限也必须一致
        assertTrue(envExample.contains("PM_AI_SCOPE_TOKEN_TTL_SECONDS=300"), envExample);
        assertTrue(compose.contains("PM_AI_SCOPE_TOKEN_TTL_SECONDS: ${PM_AI_SCOPE_TOKEN_TTL_SECONDS:-300}"), compose);
        assertTrue(composeDeploy.contains("PM_AI_SCOPE_TOKEN_TTL_SECONDS: ${PM_AI_SCOPE_TOKEN_TTL_SECONDS:-300}"),
                composeDeploy);
    }

    @Test
    void 超时链不变式成立() {
        int chatTimeout = new AiProperties().getChatTimeoutSeconds();
        int tokenTtl = ScopeTokenService.MAX_TTL_SECONDS;

        assertEquals(180, chatTimeout);
        assertEquals(300, tokenTtl);
        assertTrue(tokenTtl >= chatTimeout, "scope_token 必须 ≥ 主系统→AI 超时（否则后段回调 401）");
        assertTrue(chatTimeout >= FRONTEND_SECONDS, "主系统→AI 不能短于前端（否则前端还在等、这跳已经断了）");
        assertTrue(tokenTtl <= NGINX_SECONDS, "scope_token 不能超过 nginx 的 proxy_read_timeout");
        assertTrue(chatTimeout <= NGINX_SECONDS, "主系统→AI 不能超过 nginx（否则先被网关掐成 504）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aiSection() {
        try (InputStream in = AiTimeoutChainTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "application.yml 必须在 classpath 上");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> pm = (Map<String, Object>) root.get("pm");
            assertNotNull(pm, "application.yml 缺少 pm 段");
            Map<String, Object> ai = (Map<String, Object>) pm.get("ai");
            assertNotNull(ai, "application.yml 缺少 pm.ai 段");
            return ai;
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

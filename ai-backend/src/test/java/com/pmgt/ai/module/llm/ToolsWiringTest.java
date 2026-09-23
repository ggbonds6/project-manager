package com.pmgt.ai.module.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配自检：{@link Tools} 有两个构造器（生产用三参 + 兼容老调用点的两参），Spring 必须认三参那个。
 *
 * <p>为什么值得单开一条测试：单元测试都直接 {@code new Tools(searchPort, docStore)}，
 * **构造器选择错了它们一个都不会红**——而线上表现是"业务查询工具永远报未装配（bizQueryClient 为 null）"，
 * 甚至（若把两参标成 @Autowired）Spring 直接启动失败。这里用真上下文兜住。
 *
 * <p>{@code WebEnvironment.NONE}：不绑端口、不发任何网络请求（{@code StartupDiagnostics} 只打日志）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ToolsWiringTest {

    @Autowired
    private Tools tools;

    @Autowired
    private BizQueryClient bizQueryClient;

    @Test
    void contextWiresToolsWithBizQueryClient() {
        assertNotNull(tools, "Tools 必须是可注入的 Bean");
        assertNotNull(bizQueryClient, "BizQueryClient 必须是可注入的 Bean");
        assertTrue(tools.toolNames().contains("search_documents"));
        // 基础清单仍只有 3 个（条件注册的第四个不在里面）
        assertTrue(!tools.toolNames().contains(Tools.BIZ_QUERY_TOOL_NAME));
    }
}

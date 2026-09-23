package com.pmgt.ai.module.llm.controller;

import com.pmgt.ai.module.llm.BizQuerySpec;
import com.pmgt.ai.module.llm.QaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /chat} 新增的可选字段 {@code biz_query}（§11.2）的请求解析与路由
 * （<b>不启 Spring 上下文</b>，standalone MockMvc + 假 QaService，不联网）。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>{@code biz_query}（主名）与 {@code bizQuery}（别名）都能收——同 {@code doc_ids}/{@code docIds} 的教训，
 *       静默丢字段比 400 危险得多；</li>
 *   <li><b>不传时走老的四参入口</b>（工具清单/提示词/无文档兜底全都不变），传了才走带通道的五参入口；</li>
 *   <li>{@code scope_token} 是唯一授权凭据：<b>只用于回调主系统的 Header，绝不出现在响应里</b>。</li>
 * </ol>
 */
class ChatControllerBizQueryTest {

    private static final String TOKEN = "scope-token-should-never-be-echoed";

    /** 记录"走了哪个重载、收到了什么通道"，返回固定答案（真 QaService 要连大模型）。 */
    private static final class StubQaService extends QaService {

        private BizQuerySpec received;
        private boolean fiveArgPath;

        private StubQaService() {
            super(null, null, null);   // 只覆写两个 ask，父类协作者不会被用到
        }

        @Override
        public QaResult ask(
                String question, List<String> docIds, List<Map<String, Object>> history, Integer maxRounds) {
            this.fiveArgPath = false;
            return canned();
        }

        @Override
        public QaResult ask(
                String question,
                List<String> docIds,
                List<Map<String, Object>> history,
                Integer maxRounds,
                BizQuerySpec bizQuery) {
            this.fiveArgPath = true;
            this.received = bizQuery;
            return canned();
        }

        private static QaResult canned() {
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("ok", true);
            return new QaResult("答案", List.of(), List.of(), List.of(), llm, "done", "");
        }
    }

    private StubQaService stub;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stub = new StubQaService();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(stub)).build();
    }

    /** 老请求（没有 biz_query）：必须原样走四参入口——这是"行为与本版本前完全一致"的落点。 */
    @Test
    void withoutBizQueryUsesLegacyPath() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"中标金额是多少\",\"doc_ids\":[\"abc123\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("答案"));

        assertFalse(stub.fiveArgPath, "不传 biz_query 时必须走原来的入口");
        assertNull(stub.received);
    }

    /** 文档契约里的写法：{@code biz_query} + {@code scope_token}。 */
    @Test
    void acceptsSnakeCaseBizQuery() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"初验阶段有几个附件\",\"biz_query\":{"
                                + "\"url\":\"http://10.0.0.1:8080/api/ai/query\","
                                + "\"scope_token\":\"" + TOKEN + "\","
                                + "\"entities\":[\"projects\",\"stats\"]}}"))
                .andExpect(status().isOk());

        assertTrue(stub.fiveArgPath, "传了 biz_query 才走带通道的入口");
        assertEquals("http://10.0.0.1:8080/api/ai/query", stub.received.url());
        assertEquals(TOKEN, stub.received.scopeToken());
        assertEquals(List.of("projects", "stats"), stub.received.entities());
        assertTrue(stub.received.usable());
    }

    /** 老/前端风格的 camelCase 写法也接受（尤其是 scopeToken）。 */
    @Test
    void acceptsCamelCaseAlias() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"初验阶段有几个附件\",\"bizQuery\":{"
                                + "\"url\":\"http://10.0.0.1:8080/api/ai/query\","
                                + "\"scopeToken\":\"tok-2\",\"entities\":[\"stats\"]}}"))
                .andExpect(status().isOk());

        assertTrue(stub.fiveArgPath);
        assertEquals("tok-2", stub.received.scopeToken());
        assertEquals(List.of("stats"), stub.received.entities());
    }

    /** 只给 url/token、不给 entities 也算传了（不额外收窄）。 */
    @Test
    void entitiesAreOptional() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"预算多少\",\"biz_query\":{"
                                + "\"url\":\"http://10.0.0.1:8080/api/ai/query\","
                                + "\"scope_token\":\"tok-3\"}}"))
                .andExpect(status().isOk());

        assertTrue(stub.fiveArgPath);
        assertNull(stub.received.entities());
        assertTrue(stub.received.allows("payments"), "没给收窄清单就不限制实体");
    }

    /** 凭据只进 Header：响应里不许出现 scope_token（泄露等于把查询权限交出去）。 */
    @Test
    void scopeTokenIsNeverEchoed() throws Exception {
        MvcResult result = mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"预算多少\",\"biz_query\":{"
                                + "\"url\":\"http://10.0.0.1:8080/api/ai/query\","
                                + "\"scope_token\":\"" + TOKEN + "\"}}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(TOKEN), "响应体不得回显 scope_token：" + body);
        assertFalse(body.contains("scope_token"), "响应体里连字段名都不该出现：" + body);
    }
}

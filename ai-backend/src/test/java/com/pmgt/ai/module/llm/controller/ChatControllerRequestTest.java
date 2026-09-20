package com.pmgt.ai.module.llm.controller;

import com.pmgt.ai.module.llm.CitationRegistry;
import com.pmgt.ai.module.llm.QaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /chat} 的请求字段名与响应形状（<b>不启 Spring 上下文</b>，standalone MockMvc + 假 QaService）。
 *
 * <h2>为什么要钉住 {@code doc_ids} 与 {@code docIds} 两种写法</h2>
 *
 * <p>历史契约（{@code docs/迁移方案与对照表.md} §3 与主系统集成方案）写的是 {@code doc_ids}，
 * 而 Java DTO 字段名是 {@code docIds}，且没有配 {@code SNAKE_CASE} 命名策略——
 * 也就是说<b>按文档发的 {@code doc_ids} 会被静默忽略</b>：不报错，只是"检索范围没生效、答了全库"。
 * 这类静默失效比 400 危险得多，所以两种写法都要收，并且用测试钉住（谁把别名删了就会红）。
 */
class ChatControllerRequestTest {

    /** 只记录入参、返回固定结果的假服务（真 QaService 要连大模型，这里不碰网络）。 */
    private static final class StubQaService extends QaService {

        private List<String> receivedDocIds;
        private String receivedQuestion;

        private StubQaService() {
            super(null, null, null);   // 只覆写 ask，父类协作者不会被用到
        }

        @Override
        public QaResult ask(
                String question, List<String> docIds, List<Map<String, Object>> history, Integer maxRounds) {
            this.receivedQuestion = question;
            this.receivedDocIds = new ArrayList<>(docIds == null ? List.of() : docIds);

            // 用真实的引用表造 citations，连同响应形状一起验
            CitationRegistry citations = new CitationRegistry();
            citations.register("abc123", "合同.pdf", 3, "付款条款：验收后 30 日内支付。", 0.91);

            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("ok", true);
            llm.put("rounds", 2);
            return new QaResult(
                    "合同金额为 319.292 万元[1]",
                    List.of(),
                    citations.toList(),
                    List.of(),
                    llm,
                    "done",
                    "");
        }
    }

    private StubQaService stub;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stub = new StubQaService();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(stub)).build();
    }

    /** 文档契约里的写法（主系统按老契约发的就是它）。 */
    @Test
    void acceptsSnakeCaseDocIds() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"中标金额是多少\",\"doc_ids\":[\"abc123\",\"def456\"],"
                                + "\"history\":[{\"role\":\"user\",\"content\":\"你好\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("合同金额为 319.292 万元[1]"));

        assertEquals(List.of("abc123", "def456"), stub.receivedDocIds);
        assertEquals("中标金额是多少", stub.receivedQuestion);
    }

    /** Java DTO 原本认的写法（已经这么发的调用方不能被改挂）。 */
    @Test
    void acceptsCamelCaseDocIdsAlias() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"中标金额是多少\",\"docIds\":[\"abc123\"]}"))
                .andExpect(status().isOk());

        assertEquals(List.of("abc123"), stub.receivedDocIds);
    }

    /** 两个字段都不给时：不报错、范围为全部文档（{@code null} → QaService 用全库）。 */
    @Test
    void missingDocIdsMeansAllDocuments() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"中标金额是多少\"}"))
                .andExpect(status().isOk());

        assertEquals(List.of(), stub.receivedDocIds);
    }

    /** 响应：{@code citations} 是结构化的引用表，既有字段一个都不能少（只增不改）。 */
    @Test
    void responseContainsStructuredCitations() throws Exception {
        mvc.perform(post("/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"中标金额是多少\",\"doc_ids\":[\"abc123\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations.length()").value(1))
                .andExpect(jsonPath("$.data.citations[0].index").value(1))
                .andExpect(jsonPath("$.data.citations[0].doc_id").value("abc123"))
                .andExpect(jsonPath("$.data.citations[0].filename").value("合同.pdf"))
                .andExpect(jsonPath("$.data.citations[0].page_no").value(3))
                .andExpect(jsonPath("$.data.citations[0].snippet").value("付款条款：验收后 30 日内支付。"))
                .andExpect(jsonPath("$.data.citations[0].score").value(0.91))
                // 既有字段（老调用方按它们取数）
                .andExpect(jsonPath("$.data.trace").isArray())
                .andExpect(jsonPath("$.data.scope").isArray())
                .andExpect(jsonPath("$.data.llm.rounds").value(2))
                .andExpect(jsonPath("$.data.stopped_reason").value("done"))
                .andExpect(jsonPath("$.data.error").value(""));
    }
}

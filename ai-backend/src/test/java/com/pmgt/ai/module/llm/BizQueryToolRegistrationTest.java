package com.pmgt.ai.module.llm;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.module.retrieval.SearchPort;
import com.pmgt.ai.module.store.DocStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ① 工具**按请求注册**：不传 {@code biz_query} → 清单里没有 {@code query_business_data}；
 * 传了 → 有（且前三个工具的名字与顺序一个都没变）。
 *
 * <p>断言打在<b>真正发给模型的那份 {@code tools} 参数</b>上（{@link ToolAgent} → {@link LlmClient}），
 * 而不是只看 {@link Tools} 自己的方法——"模型看不看得见"才是这条契约的意义所在。
 *
 * <p>同时钉住 schema 本身：{@code entity} 是四值枚举、{@code required=[entity]}、
 * 描述里写死了"必须用它…不得靠文档推测"以及**每个 entity 支持的 filters 字段与示例**（§11.3/§11.4）。
 */
class BizQueryToolRegistrationTest {

    /** 只记录"这次给了模型哪些工具"，不碰任何网络。 */
    private static final class CapturingLlmClient extends LlmClient {

        private List<Map<String, Object>> lastTools = List.of();

        private CapturingLlmClient() {
            super(new AiSettings());
        }

        @Override
        public ChatResult chatMessages(
                List<Map<String, Object>> messages,
                List<Map<String, Object>> tools,
                Integer maxTokens,
                Double timeoutSeconds) {
            this.lastTools = tools == null ? List.of() : tools;
            return new ChatResult("答案", "stub", 1, 1, "", "stop", List.of());
        }

        /** 本次实际发出去的工具名（保序）。 */
        private List<String> names() {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> schema : lastTools) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) schema.get("function");
                out.add(String.valueOf(fn.get("name")));
            }
            return out;
        }

        private Map<String, Object> schemaOf(String name) {
            for (Map<String, Object> schema : lastTools) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) schema.get("function");
                if (name.equals(fn.get("name"))) {
                    return schema;
                }
            }
            return null;
        }
    }

    /** 空检索替身（注册测试不会执行任何工具）。 */
    private static final class EmptySearchPort implements SearchPort {
        @Override
        public SearchResult search(String query, Integer topK, String docId) {
            return new SearchResult(0, List.of(), "keyword", false, "");
        }
    }

    private static final List<String> BASE =
            List.of("search_documents", "read_page", "calculate");

    @TempDir
    Path tempDir;

    private Tools tools;
    private CapturingLlmClient llm;
    private ToolAgent agent;
    private BizQuerySpec spec;

    @BeforeEach
    void setUp() {
        tools = new Tools(new EmptySearchPort(), new DocStore(tempDir));
        llm = new CapturingLlmClient();
        agent = new ToolAgent(llm, tools);
        spec = new BizQuerySpec("http://10.0.0.1:8080/api/ai/query", "scope-token", Tools.BIZ_ENTITIES);
    }

    private void runAgent(BizQuerySpec bizQuery) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", "这个项目初验阶段有几个附件？");
        messages.add(user);
        agent.run(messages, 2, 5.0, null, bizQuery);
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 按请求注册
    // ══════════════════════════════════════════════════════════════════

    /** 不传 {@code biz_query}：工具清单与老版本逐字节一致（模型的 tool_calls 里不可能出现它）。 */
    @Test
    void withoutBizQueryTheToolIsNotRegistered() {
        runAgent(null);

        assertEquals(BASE, llm.names());
        assertFalse(llm.names().contains(Tools.BIZ_QUERY_TOOL_NAME));
        // Tools 的默认清单同样不含它（老调用点走的就是这个）
        assertEquals(Set.of("search_documents", "read_page", "calculate"), tools.toolNames());
        assertEquals(BASE, namesOf(tools.schemas()));
    }

    /** 传了 {@code biz_query}：第 4 个工具出现，且前三个的名字与顺序不变（老提示词的引用仍然有效）。 */
    @Test
    void withBizQueryTheToolIsRegistered() {
        runAgent(spec);

        assertEquals(List.of("search_documents", "read_page", "calculate", "query_business_data"),
                llm.names());
        assertEquals(Set.of("search_documents", "read_page", "calculate", "query_business_data"),
                tools.toolNames(spec));
    }

    /** 两个清单的差集<b>只有</b>这一个工具（别顺手把别的工具也塞进条件分支）。 */
    @Test
    void conditionalRegistrationAddsExactlyOneTool() {
        Set<String> base = new LinkedHashSet<>(namesOf(tools.schemas(null)));
        Set<String> withBiz = new LinkedHashSet<>(namesOf(tools.schemas(spec)));

        assertTrue(withBiz.removeAll(base));
        assertEquals(Set.of(Tools.BIZ_QUERY_TOOL_NAME), withBiz);
    }

    // ══════════════════════════════════════════════════════════════════
    // schema（§11.3 的参数 + §11.5 的描述）
    // ══════════════════════════════════════════════════════════════════

    @Test
    void schemaHasEntityEnumFiltersAndLimit() {
        runAgent(spec);
        Map<String, Object> schema = llm.schemaOf(Tools.BIZ_QUERY_TOOL_NAME);
        assertNotNull(schema, "带了 biz_query 就必须能拿到该工具的 schema");

        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        assertEquals("function", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals("object", parameters.get("type"));
        assertEquals(List.of("entity"), parameters.get("required"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertEquals(List.of("entity", "filters", "limit"), new ArrayList<>(properties.keySet()));

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) properties.get("entity");
        assertEquals("string", entity.get("type"));
        assertEquals(List.of("projects", "contracts", "payments", "stats"), entity.get("enum"));

        @SuppressWarnings("unchecked")
        Map<String, Object> filters = (Map<String, Object>) properties.get("filters");
        assertEquals("object", filters.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) properties.get("limit");
        assertEquals("integer", limit.get("type"));
        assertTrue(String.valueOf(limit.get("description")).contains("20"));
        assertTrue(String.valueOf(limit.get("description")).contains("100"));
    }

    /**
     * 描述里必须写死"这类事实必须用它、不得靠文档推测"，并且<b>逐实体列出 filters 字段与示例</b>
     * （模型照着编 filters 是最容易出错的地方；字段清单只来自 §11.4）。
     */
    @Test
    void schemaDescriptionListsFiltersPerEntity() {
        runAgent(spec);
        @SuppressWarnings("unchecked")
        Map<String, Object> fn =
                (Map<String, Object>) llm.schemaOf(Tools.BIZ_QUERY_TOOL_NAME).get("function");
        String description = String.valueOf(fn.get("description"));

        assertTrue(description.contains("项目/合同/付款/附件数量/统计口径这类事实必须用它"), description);
        assertTrue(description.contains("不得靠文档推测、不得自行推算"), description);
        // 四个 entity 都要出现，且各自的 filters 字段与示例要写出来
        for (String entity : Tools.BIZ_ENTITIES) {
            assertTrue(description.contains(entity), "描述里缺少 entity：" + entity);
        }
        for (String field : List.of("projectId", "name", "status", "type", "year",
                "vendorName", "nodeCode", "kind", "phase_attachment_count", "type_distribution",
                "year_amount")) {
            assertTrue(description.contains(field), "描述里缺少 filters 字段：" + field);
        }
        assertTrue(description.contains("{\"projectId\":12}"), "缺少 filters 示例");
        assertTrue(description.contains("caliber") && description.contains("data_time"),
                "要教会模型转述口径与数据时间");
    }

    private static List<String> namesOf(List<Map<String, Object>> schemas) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> schema : schemas) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) schema.get("function");
            out.add(String.valueOf(fn.get("name")));
        }
        return out;
    }
}

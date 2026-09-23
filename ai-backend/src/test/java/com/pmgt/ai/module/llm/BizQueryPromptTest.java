package com.pmgt.ai.module.llm;

import com.pmgt.ai.common.config.AiSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ③ 提示词路由规则：三条规则**只在 {@code biz_query} 存在时**才进 system。
 *
 * <p>两个方向的失败都很贵，所以两边都钉：
 * <ul>
 *   <li>没开通却写了工具名 → 模型会去调一个**不存在**的工具，白烧一轮往返；</li>
 *   <li>开通了却没写规则 → 模型继续拿文档内容/常识顶替系统事实（P2 要解决的就是这个）。</li>
 * </ul>
 */
class BizQueryPromptTest {

    private static final BizQuerySpec SPEC =
            new BizQuerySpec("http://10.0.0.1:8080/api/ai/query", "scope-token", List.of());

    private static final List<Map<String, Object>> DOCS = List.of(
            Map.of("doc_id", "aaaa1111", "filename", "合同.pdf", "pages", 3));

    private final QaService qaService = new QaService(new AiSettings(), null, null);

    private static String systemOf(QaService.BuiltMessages built) {
        assertEquals("system", built.messages().get(0).get("role"), "第一条必须是 system（推理服务只认一条）");
        return String.valueOf(built.messages().get(0).get("content"));
    }

    private static long systemCount(QaService.BuiltMessages built) {
        return built.messages().stream().filter(m -> "system".equals(m.get("role"))).count();
    }

    /** 不传 biz_query：system 里一个字都不许提 {@code query_business_data}（老行为逐字节不变）。 */
    @Test
    void promptDoesNotMentionBizToolWithoutChannel() {
        String system = systemOf(qaService.buildQaMessages("这个项目有几个附件", DOCS, List.of()));

        assertFalse(system.contains("query_business_data"), "没注册的工具不许出现在提示词里");
        assertFalse(system.contains("业务数据路由规则"));
        // 老提示词的关键约束仍在
        assertTrue(system.contains("search_documents"));
        assertTrue(system.contains("当前可检索的文档共 1 份"));
    }

    /** 传了 biz_query：§11.5 的三条规则逐条落地。 */
    @Test
    void promptCarriesThreeRoutingRulesWithChannel() {
        String system = systemOf(qaService.buildQaMessages("这个项目有几个附件", DOCS, List.of(), SPEC));

        // ① 事实必须走工具；查不到说"系统里没有"，不得用文档/常识顶替
        assertTrue(system.contains("query_business_data"));
        assertTrue(system.contains("**必须调用 `query_business_data`**"), system);
        assertTrue(system.contains("系统里没有"));
        assertTrue(system.contains("用文档内容或常识顶替"));
        assertTrue(system.contains("不得用文档里的说法推测"));
        // ② 文档内容仍走检索 + [cite]
        assertTrue(system.contains("`search_documents` / `read_page`"));
        assertTrue(system.contains("[cite]"));
        // ③ 引用系统数字必须带口径与数据时间；混用时分开陈述
        assertTrue(system.contains("caliber"));
        assertTrue(system.contains("data_time"));
        assertTrue(system.contains("分开陈述"));
        assertTrue(system.contains("系统数据：…；文档依据：…[1]"), system);
        // 失败与"没有数据"必须区分开
        assertTrue(system.contains("该范围内没有匹配数据"));
        assertTrue(system.contains("说成\"没有数据\""), system);
        assertTrue(system.contains("调用失败或越权"));
    }

    /** 仍然只有一条 system、且在最前面（推理服务遇到第二条 system 会直接 400）。 */
    @Test
    void stillASingleSystemMessageAtTheTop() {
        QaService.BuiltMessages built =
                qaService.buildQaMessages("这个项目有几个附件", DOCS, List.of(), SPEC);

        assertEquals(1, systemCount(built));
        assertEquals("system", built.messages().get(0).get("role"));
        assertEquals(2, built.messages().size(), "没有历史时就是 system + user");
    }

    /** 授权实体清单（biz_query.entities）写进提示词：模型第一轮就别试范围外的实体。 */
    @Test
    void authorizedEntitiesAreListedInPrompt() {
        BizQuerySpec narrow =
                new BizQuerySpec("http://10.0.0.1:8080/api/ai/query", "scope-token", List.of("projects", "stats"));

        String system = systemOf(qaService.buildQaMessages("有几个附件", DOCS, List.of(), narrow));

        assertTrue(system.contains("本次 `biz_query` 授权的实体：projects、stats"), system);
        assertTrue(system.contains("一律不要调用"));
    }

    /** entities 为空时不额外收窄：不出现"授权的实体"那句话。 */
    @Test
    void emptyEntitiesMeansNoNarrowing() {
        String system = systemOf(qaService.buildQaMessages("有几个附件", DOCS, List.of(), SPEC));
        assertFalse(system.contains("授权的实体"));
    }
}

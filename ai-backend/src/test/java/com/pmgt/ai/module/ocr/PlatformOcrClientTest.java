package com.pmgt.ai.module.ocr;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.util.ProgressFn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlatformOcrClient} 响应解析的回归测试（**纯离线**：只喂构造出来的 JSON，不发任何请求）。
 *
 * <p>沿用 Python 版 {@code ai-service/tests/test_platform_ocr.py}（那是行为规格），固化的就是注释里那两条实测结论：
 * ① {@code blocks} 的位置随请求形式变化——批量请求只在 {@code results[i]} 里；
 * ② 平台**不返回置信度**，代码不许自己编一个出来。
 *
 * <p>因为解析是纯静态函数（{@link PlatformOcrClient#pagesFromResponse} / {@link PlatformOcrClient#parseResponse}），
 * 这里直接测它，不碰网络、不起线程。
 */
class PlatformOcrClientTest {

    // ── ① blocks 在 results[i] 里，不在顶层 ───────────────────────────

    @Test
    @DisplayName("批量响应只认 results：顶层放的诱饵块必须被忽略")
    void batchResponseReadsBlocksFromResults() {
        // 挡的是：批量请求时"块全丢"。批量（{"images": [...]}）响应的顶层**没有** blocks，
        // 页级内容在 results[i]。这里故意同时塞一个顶层 blocks 当诱饵：解析必须只认 results，
        // 否则一旦服务端两种形式都返回，就会拿到错位/重复的块（出处定位跟着错）。
        String json = """
                {
                  "markdown": "顶层诱饵，不该被用到",
                  "blocks": [{"label": "decoy", "content": "顶层诱饵"}],
                  "results": [
                    {"markdown": "第一页",
                     "blocks": [{"label": "text", "content": "甲", "bbox": [1, 2, 3, 4]}],
                     "width": 1200, "height": 1600},
                    {"markdown": "第二页", "blocks": [{"label": "table", "content": "乙"}]}
                  ]
                }
                """;

        List<PlatformPage> pages = PlatformOcrClient.parseResponse(json, 2);

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(PlatformPage::markdown).containsExactly("第一页", "第二页");
        assertThat(pages.get(0).blocks()).hasSize(1);
        assertThat(pages.get(0).blocks().get(0).label()).isEqualTo("text");
        assertThat(pages.get(0).blocks().get(0).text()).isEqualTo("甲");
        assertThat(pages.get(0).blocks().get(0).bbox()).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(pages.get(1).blocks().get(0).label()).isEqualTo("table");
        assertThat(pages.get(1).blocks().get(0).text()).isEqualTo("乙");
        assertThat(pages.get(0).ok()).isTrue();
        assertThat(pages.get(0).error()).isNull();
        // 诱饵一个都不许漏进来
        assertThat(pages).allSatisfy(p -> assertThat(p.blocks())
                .noneMatch(b -> "decoy".equals(b.label()) || "顶层诱饵".equals(b.text())));
    }

    @Test
    @DisplayName("单页响应没有 results 时退回顶层字段，并且印章块（内容为空）要保留")
    void singleResponseFallsBackToTopLevel() {
        // 挡的是：单张请求（{"image": ...}）没走通。单页响应的 blocks 在**顶层**且没有 results，
        // 实现里有一条 n == 1 的顶层兜底。顺带钉住印章信号：label == "seal" 的块默认就返回（内容为空），
        // 数量本身就是"这页有章"的免费探测——别把它当噪声丢掉。
        String json = """
                {
                  "markdown": "单页文本",
                  "blocks": [{"label": "seal", "content": ""}],
                  "width": 800,
                  "height": 600
                }
                """;

        List<PlatformPage> pages = PlatformOcrClient.parseResponse(json, 1);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).markdown()).isEqualTo("单页文本");
        assertThat(pages.get(0).blocks()).hasSize(1);
        assertThat(pages.get(0).blocks().get(0).label()).isEqualTo("seal");
        assertThat(pages.get(0).blocks().get(0).text()).isEmpty();
        assertThat(pages.get(0).sealCount()).isEqualTo(1);
        // 注：Python 的 PlatformPage 还带 width/height（800/600），Java 接缝（frozen 的 PlatformPage record）
        // 没有这两个字段，正文链路也不用它们——这里就不断言了，免得为了对齐而扩别人的接缝。
    }

    @Test
    @DisplayName("顶层兜底只对单页生效：批量（n>1）缺 results 时如实返回空页")
    void topLevelFallbackOnlyAppliesToSinglePage() {
        // 挡的是：顶层兜底被"顺手"放大到批量。兜底条件里带 n == 1：批量（n > 1）缺 results 时必须如实返回空页，
        // 不能把顶层那一段文本复制到每一页——那是凭空造出重复内容，比空着更危险。
        List<PlatformPage> pages = PlatformOcrClient.parseResponse(
                "{\"markdown\": \"顶层\", \"blocks\": [{\"label\": \"text\"}]}", 2);

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(PlatformPage::markdown).containsExactly("", "");
        assertThat(pages.get(0).blocks()).isEmpty();
        assertThat(pages.get(1).blocks()).isEmpty();
    }

    // ── 页数对不齐 ──────────────────────────────────────────────────

    @Test
    @DisplayName("页数对不上不抛异常：缺的补空页、多的丢掉、空响应给空页")
    void pageCountMismatchFillsEmptyPagesWithoutRaising() {
        // 挡的是：页数对不上时抛异常或张冠李戴。实现的口径：按请求的 n 输出，缺的页补空 PlatformPage
        // （markdown=""、blocks=[]、宽高 0、ok == true——空页不等于"这页识别失败"）；多出来的页直接丢掉。
        // 页号靠列表下标对齐，所以顺序绝不能乱。
        List<PlatformPage> shortResp = PlatformOcrClient.parseResponse("{\"results\": [{\"markdown\": \"只有一页\"}]}", 3);
        assertThat(shortResp).extracting(PlatformPage::markdown).containsExactly("只有一页", "", "");
        assertThat(shortResp.get(2).blocks()).isEmpty();
        assertThat(shortResp.get(2).markdown()).isEmpty();
        assertThat(shortResp.get(2).ok()).isTrue();

        // 响应比请求多：多余的页丢掉（返回的页数必须等于请求的页数）
        assertThat(PlatformOcrClient.parseResponse("{\"results\": [{\"markdown\": \"a\"}, {\"markdown\": \"b\"}]}", 1))
                .hasSize(1);

        // 完全空响应 / 字段缺失：不炸，如实给空页
        assertThat(PlatformOcrClient.parseResponse("{}", 2)).extracting(PlatformPage::markdown).containsExactly("", "");
        assertThat(PlatformOcrClient.parseResponse("{\"results\": [{}]}", 1).get(0).markdown()).isEmpty();
    }

    @Test
    @DisplayName("空串 / 非法 JSON / null 都不抛异常，按空页处理")
    void emptyOrBrokenJsonDoesNotThrow() {
        assertThat(PlatformOcrClient.parseResponse("", 2)).extracting(PlatformPage::markdown).containsExactly("", "");
        assertThat(PlatformOcrClient.parseResponse("   ", 2)).hasSize(2);
        assertThat(PlatformOcrClient.parseResponse(null, 1)).hasSize(1);
        assertThat(PlatformOcrClient.parseResponse("这不是 JSON", 2)).extracting(PlatformPage::markdown)
                .containsExactly("", "");
        assertThat(PlatformOcrClient.parseResponse("[1, 2, 3]", 2)).hasSize(2);
    }

    // ── ② 不许编置信度 ──────────────────────────────────────────────

    @Test
    @DisplayName("PlatformPage 里根本没有 confidence 字段（不许编一个出来）")
    void platformPageNeverCarriesConfidence() {
        // 挡的是：给平台结果"编一个置信度"。注释写明"平台不返回置信度"，而历史上出过
        // "OCR 平均置信度 0.97 的文件里金额被认错"的教训——编一个 0.9x 出来，
        // 等于把人骗去相信一个不存在的信号。实现的做法是 PlatformPage 连这个字段都没有，上层只能填 None。
        assertThat(Arrays.stream(PlatformPage.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("confidence");
        assertThat(Arrays.stream(PlatformPage.Block.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("confidence");
    }

    // ── 印章块计数（免费探测信号）────────────────────────────────────

    @Test
    @DisplayName("印章块按 label 计数：内容是空的，但数量本身就是'这页有章'")
    void sealBlocksAreCounted() {
        String json = """
                {"results": [
                  {"markdown": "含章页", "blocks": [
                     {"label": "seal", "content": ""},
                     {"label": "seal", "content": ""},
                     {"label": "text", "content": "正文"}
                  ]},
                  {"markdown": "表格页", "blocks": [{"label": "table", "content": "<table></table>"}]}
                ]}
                """;

        List<PlatformPage> pages = PlatformOcrClient.parseResponse(json, 2);

        assertThat(pages.get(0).sealCount()).isEqualTo(2);
        assertThat(pages.get(0).tableCount()).isZero();
        assertThat(pages.get(0).countLabel("text")).isEqualTo(1);
        assertThat(pages.get(1).tableCount()).isEqualTo(1);
        assertThat(pages.get(1).sealCount()).isZero();
        // 印章块的内容为空——"有章"是免费得到的，但文字要靠 seal=true 单跑（默认不开）
        assertThat(pages.get(0).blocks().get(0).text()).isEmpty();
    }

    @Test
    @DisplayName("blocks 字段兼容 content / text 两种写法；bbox 缺了就是 null，不编坐标")
    void blockFieldsFallBackAndBboxMayBeNull() {
        String json = """
                {"results": [{"blocks": [
                  {"label": "text", "text": "只有 text 字段"},
                  {"label": "title", "content": "有 content"}
                ]}]}
                """;

        List<PlatformPage.Block> blocks = PlatformOcrClient.parseResponse(json, 1).get(0).blocks();

        assertThat(blocks).extracting(PlatformPage.Block::text).containsExactly("只有 text 字段", "有 content");
        assertThat(blocks.get(0).bbox()).isNull();
    }

    // ── 不变式：空输入不碰网络、批大小上限 ───────────────────────────

    @Test
    @DisplayName("空输入不组装 payload、不发请求；单次请求上限 16 页")
    void emptyInputDoesNotCallPlatformAndBatchLimitIs16() {
        // 挡的是：空输入也去发一次请求。空列表必须在组装 payload 之前就返回（全程不碰网络），
        // 顺带钉住手册上限：单次请求 ≤16 页。
        PlatformOcrClient client = new PlatformOcrClient(new AiSettings());

        assertThat(client.recognize(List.of(), ProgressFn.NONE)).isEmpty();
        assertThat(client.recognize(null)).isEmpty();
        assertThat(PlatformOcrClient.MAX_BATCH_PAGES).isEqualTo(16);
        assertThat(new AiSettings().getOcr().getBatchPages()).isLessThanOrEqualTo(PlatformOcrClient.MAX_BATCH_PAGES);
    }

    // ── 接缝：Bean 注解 + /health 的字段形状 ─────────────────────────

    @Test
    @DisplayName("两个类都是 @Component（DocumentReader 与 /health 按 Bean 注入）")
    void ocrComponentsAreSpringBeans() {
        assertThat(PlatformOcrClient.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(PdfReader.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    @DisplayName("/health 的 ocr 块字段名与 Python 一致：ok/workers/idle/options/detail")
    void ocrHealthExposesPythonFieldNames() {
        OcrHealth health = new OcrHealth(true, 80, 80, List.of("seal", "chart"), "workers=80 idle=80");

        assertThat(health.ok()).isTrue();
        assertThat(health.workers()).isEqualTo(80);
        assertThat(health.idle()).isEqualTo(80);
        assertThat(health.options()).containsExactly("seal", "chart");
        assertThat(health.detail()).isEqualTo("workers=80 idle=80");

        Map<String, Object> map = health.asMap();
        assertThat(map).containsOnlyKeys("ok", "workers", "idle", "options", "detail");
        assertThat(map.get("options")).isEqualTo(List.of("seal", "chart"));

        // 缓存时间戳不进对外字段（Python 的 ts 是内部实现细节）
        assertThat(OcrHealth.down("HTTP 401: invalid api key（401 检查 sk 是否有效）").asMap())
                .containsOnlyKeys("ok", "workers", "idle", "options", "detail");
    }
}

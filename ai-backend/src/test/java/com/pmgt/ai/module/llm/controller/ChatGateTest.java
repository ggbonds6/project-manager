package com.pmgt.ai.module.llm.controller;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.gate.PipelineGate;
import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.module.llm.QaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /chat} 的 L1 交互式准入：<b>1.5s 快速失败 ⇒ 503</b>（不排队到用户 180s 超时）。
 *
 * <p>这条纪律是设计 §5.2/§5.4 的硬要求，也是"交互式不被批量饿死"（V4）的落地形态：
 * 交互式通道<b>默认队列 0</b>，等不到许可就是明确的 503 而不是默默排队。
 */
class ChatGateTest {

    /** 只返回固定结果、不碰网络的假问答服务。 */
    private static final class StubQaService extends QaService {

        private final List<String> calls = new ArrayList<>();

        private StubQaService() {
            super(null, null, null);
        }

        @Override
        public QaResult ask(String question, List<String> docIds, List<Map<String, Object>> history,
                            Integer maxRounds, com.pmgt.ai.module.llm.BizQuerySpec bizQuery,
                            com.pmgt.ai.module.retrieval.SearchScope requestScope) {
            calls.add(question);
            Map<String, Object> llm = new LinkedHashMap<>();
            llm.put("ok", true);
            return new QaResult("答案", List.of(), List.of(), List.of(), llm, "done", "");
        }
    }

    private static PipelineGate gate(int interactiveMax, long waitMs) {
        AiSettings settings = new AiSettings();
        settings.getGate().getInteractive().setMax(interactiveMax);
        settings.getGate().getInteractive().setQueue(0);
        settings.getGate().getInteractive().setWaitMs(waitMs);
        return new PipelineGate(settings, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("L1 满时 /chat 是 503 快速失败：等待不超过等待上限（不排队到 180s），且没调用模型")
    void chatFailsFastWith503WhenInteractiveSaturated() {
        PipelineGate gate = gate(1, 300);
        StubQaService qa = new StubQaService();
        ChatController controller = new ChatController(qa, gate);

        // 占住交互式唯一许可（模拟"已有 1 个问答在跑"）
        try (var busy = gate.admitInteractive("P1")) {
            assertTrue(busy.admitted());

            ChatController.ChatIn payload = new ChatController.ChatIn();
            payload.setQuestion("还有人吗");
            long started = System.nanoTime();
            ApiException error = assertThrows(ApiException.class, () -> controller.chat(payload));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertEquals(503, error.getStatus(), "交互式繁忙必须是 503（可重试），不是 500/429");
            assertTrue(elapsedMs <= 1500, "必须快速失败（实测 " + elapsedMs + "ms，上限 1500ms）");
            assertTrue(error.getMessage().contains("交互式"), error.getMessage());
            assertTrue(error.getMessage().contains("在飞 1/1"), error.getMessage());
            assertTrue(qa.calls.isEmpty(), "被拒的请求绝不能真的去调模型");
        }
    }

    @Test
    @DisplayName("有空位时 /chat 正常跑，且问答结束后 L1 permit 立即归还（失败路径也不例外）")
    void chatReleasesPermitAfterCompletion() {
        PipelineGate gate = gate(1, 300);
        StubQaService qa = new StubQaService();
        ChatController controller = new ChatController(qa, gate);

        ChatController.ChatIn payload = new ChatController.ChatIn();
        payload.setQuestion("合同金额是多少");
        Map<String, Object> body = controller.chat(payload);
        assertEquals(0, body.get("code"));
        assertEquals(1, qa.calls.size());
        assertEquals(0, gate.snapshotQueueInflight("interactive"), "问答结束后必须归还 L1 permit（否则 V5 泄漏）");
        assertTrue(gate.snapshotAllInflightZero());

        // 再问一次仍然能进（说明许可真的还回去了）
        controller.chat(payload);
        assertEquals(2, qa.calls.size());
        assertEquals(0, gate.snapshotQueueInflight("interactive"));
    }

    @Test
    @DisplayName("问答内部抛异常也必须归还 permit（H1：L1 覆盖全程，异常路径不漏）")
    void chatReleasesPermitEvenWhenQaFails() {
        PipelineGate gate = gate(1, 300);
        QaService boom = new QaService(null, null, null) {
            @Override
            public QaResult ask(String question, List<String> docIds, List<Map<String, Object>> history,
                                Integer maxRounds, com.pmgt.ai.module.llm.BizQuerySpec bizQuery,
                                com.pmgt.ai.module.retrieval.SearchScope requestScope) {
                throw new IllegalStateException("模型炸了");
            }
        };
        ChatController controller = new ChatController(boom, gate);
        ChatController.ChatIn payload = new ChatController.ChatIn();
        payload.setQuestion("x");
        assertThrows(IllegalStateException.class, () -> controller.chat(payload));
        assertEquals(0, gate.snapshotQueueInflight("interactive"), "异常路径同样要归还 permit");
        assertTrue(gate.snapshotAllInflightZero());
    }

    @Test
    @DisplayName("不接闸门（老装配）时行为与接入前一致：直接跑问答")
    void chatWithoutGateStillWorks() {
        StubQaService qa = new StubQaService();
        ChatController controller = new ChatController(qa);
        ChatController.ChatIn payload = new ChatController.ChatIn();
        payload.setQuestion("x");
        assertEquals(0, controller.chat(payload).get("code"));
        assertEquals(1, qa.calls.size());
    }

    @Test
    @DisplayName("作用域里唯一的 project_id 会作为 L1 公平维度（按项目轮转的前提）")
    void chatUsesProjectIdFromScope() {
        PipelineGate gate = gate(2, 300);
        StubQaService qa = new StubQaService();
        ChatController controller = new ChatController(qa, gate);

        ChatController.ChatIn payload = new ChatController.ChatIn();
        payload.setQuestion("x");
        ChatController.ScopeIn scope = new ChatController.ScopeIn();
        scope.setProjectIds(List.of("PROJECT_42"));
        payload.setScope(scope);
        controller.chat(payload);

        Object fairness = gate.snapshot().get("fairness");
        assertTrue(fairness.toString().contains("PROJECT_42"),
                "交互式的公平维度必须来自请求作用域：" + fairness);
    }
}

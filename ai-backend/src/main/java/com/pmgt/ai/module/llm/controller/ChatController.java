package com.pmgt.ai.module.llm.controller;

import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.module.llm.QaService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 文档问答：基于已入库文档，用工具调用（检索 → 读页 → 计算）产出**带来源页码**的答案。
 *
 * <p>请求体字段与 Python 版一致（`question` / `doc_ids` / `history`），
 * 主系统前端按老契约调用，换语言不该让它改代码。
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final QaService qaService;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatIn payload) {
        return ApiResponse.ok(
                qaService.ask(payload.getQuestion(), payload.getDocIds(), payload.getHistory(), null).toDict());
    }

    @Data
    public static class ChatIn {
        private String question = "";
        private List<String> docIds;
        private List<Map<String, Object>> history;
    }
}

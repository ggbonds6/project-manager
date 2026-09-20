package com.pmgt.ai.module.llm.controller;

import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.common.web.TempUploads;
import com.pmgt.ai.module.llm.AnalyzeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

/**
 * 抽取：附件 → 结构化 markdown（带来源页码与置信度标注）。
 *
 * <p>同步接口（走一次大模型，几秒到一两分钟）；大文件建议先 `/documents` 入库再提问。
 */
@RestController
@RequiredArgsConstructor
public class AnalyzeController {

    private final AnalyzeService analyzeService;
    private final TempUploads tempUploads;

    @PostMapping("/analyze")
    public Map<String, Object> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "instruction", defaultValue = "") String instruction,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi) {
        Path path = tempUploads.save(file);
        try {
            return ApiResponse.ok(
                    analyzeService.analyze(path, instruction, dpi > 0 ? dpi : null).toPayload());
        } finally {
            tempUploads.deleteQuietly(path);
        }
    }
}

package com.pmgt.ai.module.task.controller;

import com.pmgt.ai.common.web.ApiException;
import com.pmgt.ai.common.web.ApiResponse;
import com.pmgt.ai.common.web.TempUploads;
import com.pmgt.ai.module.task.UploadTask;
import com.pmgt.ai.module.task.UploadTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

/**
 * 上传解析任务：**先返回、后解析**，前端轮询进度。
 *
 * <p>为什么不放在 `/documents` 里同步做：解析一份扫描件要几十秒到几分钟，
 * 同步接口必然被超时打断（Python 版实测三层超时叠加），用户看到的是"上传失败"，
 * 而其实文件已经收到了。所以拆成"登记任务 + 轮询状态"。
 */
@RestController
@RequestMapping("/upload-tasks")
@RequiredArgsConstructor
public class UploadTaskController {

    private final UploadTaskService taskService;
    private final TempUploads tempUploads;

    @PostMapping
    public Map<String, Object> create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dpi", defaultValue = "0") int dpi) {
        Path path = tempUploads.save(file);
        UploadTask task = taskService.submit(
                path, tempUploads.originalName(file), file.getSize(), dpi > 0 ? dpi : null);
        return ApiResponse.ok(task.toBrief());
    }

    @GetMapping
    public Map<String, Object> list() {
        return ApiResponse.ok(taskService.list());
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> get(@PathVariable String taskId) {
        UploadTask task = taskService.get(taskId);
        if (task == null) {
            throw ApiException.notFound("任务不存在");
        }
        return ApiResponse.ok(task.toDetail());
    }

    /**
     * `remove=true` 移除记录（仅限已结束），否则取消任务。
     *
     * <p>分开两种语义是刻意的：取消是"别跑了"，移除是"从列表里清掉"；
     * 未结束的任务不允许直接移除，否则会出现"任务还在跑但记录没了"。
     */
    @DeleteMapping("/{taskId}")
    public Map<String, Object> delete(
            @PathVariable String taskId,
            @RequestParam(name = "remove", defaultValue = "false") boolean remove) {
        if (remove) {
            if (!taskService.remove(taskId)) {
                throw ApiException.badRequest("任务不存在或仍在进行中");
            }
            return ApiResponse.ok(Map.of("removed", taskId));
        }
        if (!taskService.cancel(taskId)) {
            throw ApiException.badRequest("任务不存在或已结束");
        }
        return ApiResponse.ok(Map.of("cancelled", taskId));
    }
}

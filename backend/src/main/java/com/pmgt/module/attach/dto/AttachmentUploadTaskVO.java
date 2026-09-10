package com.pmgt.module.attach.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台上传任务（上传记录）视图对象。
 * 不含 temp_path 等内部路径，避免泄露服务端目录结构。
 */
@Data
public class AttachmentUploadTaskVO {

    private Long id;
    private Long projectId;
    private String bizType;
    private Long bizId;
    private String attachType;
    private String fileName;
    private Long fileSize;
    private String fileExt;
    /** PENDING | UPLOADING | SUCCESS | FAILED */
    private String status;
    private Integer progress;
    private String errorMsg;
    /** 成功后关联的正式附件 id（前端据此刷新列表/预览） */
    private Long attachmentId;
    private Long uploadUserId;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}

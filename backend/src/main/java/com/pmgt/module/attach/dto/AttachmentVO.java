package com.pmgt.module.attach.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AttachmentVO {

    private Long id;
    private String bizType;
    private Long bizId;
    /** bizType=PROJECT_PHASE 时附带阶段名，便于附件中心按阶段分组 */
    private Long phaseId;
    private String phaseName;
    private String attachType;
    private String fileName;
    private Long fileSize;
    private String fileExt;
    private Long uploadUserId;
    private String uploadUserName;
    private LocalDateTime uploadTime;
}

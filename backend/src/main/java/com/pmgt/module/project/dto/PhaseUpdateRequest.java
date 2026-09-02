package com.pmgt.module.project.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 阶段实例更新（可部分字段）。
 */
@Data
public class PhaseUpdateRequest {

    private String status;
    private Integer percent;
    private String note;
    private LocalDate planStartDate;
    private LocalDate planFinishDate;
    private LocalDate actualStartDate;
    private LocalDate actualFinishDate;
    private Long managerUserId;
    /** 阶段关键结果字段，整体替换保存 */
    private Map<String, Object> resultFields;
}

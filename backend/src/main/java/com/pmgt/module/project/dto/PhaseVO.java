package com.pmgt.module.project.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 阶段实例（详情页使用）。
 */
@Data
public class PhaseVO {

    private Long id;
    private Long projectId;
    private String phaseName;
    private Integer sortNo;
    private Integer weight;
    private String payNode;
    private String status;
    private Integer percent;
    private LocalDate planStartDate;
    private LocalDate planFinishDate;
    private LocalDate actualStartDate;
    private LocalDate actualFinishDate;
    private Long managerUserId;
    private String managerName;
    private String note;
    private Object resultFields;
    private LocalDateTime updateTime;
}

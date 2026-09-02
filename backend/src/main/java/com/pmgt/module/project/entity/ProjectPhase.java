package com.pmgt.module.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pmgt.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_phase")
public class ProjectPhase extends BaseEntity {

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
    private String note;
    /** 阶段关键结果字段：JSON 文本 */
    private String resultFields;
}

package com.pmgt.module.log.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志：只追加、不物理删除。
 */
@Data
@TableName("operate_log")
public class OperateLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String userName;
    private String bizType;
    private Long bizId;
    private String action;
    private String detail;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

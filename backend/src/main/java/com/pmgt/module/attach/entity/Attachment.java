package com.pmgt.module.attach.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 附件元数据（独立时间字段 upload_time，逻辑删除不物理删文件，便于审计追溯）。
 */
@Data
@TableName("attachment")
public class Attachment implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;
    private Long bizId;
    private String attachType;
    private String fileName;
    private String storedName;
    private String filePath;
    private Long fileSize;
    private String fileExt;
    private Long uploadUserId;
    private LocalDateTime uploadTime;

    @TableLogic
    private Integer deleted;
}

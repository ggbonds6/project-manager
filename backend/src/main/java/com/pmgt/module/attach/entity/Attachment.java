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

    /**
     * AI 可检索状态：NOT_PARSED / PARSING / READY / FAILED。
     *
     * <p>V13 新增，可空：历史附件读出来是 {@code null}，语义等同 {@code NOT_PARSED}
     * ——所以判定一律走 {@link #getAiIndexStatus()}（见下方重写），不要让调用方各自判空，
     * 否则「存量数据看起来像状态未知」会被各处漏判。
     */
    private String aiIndexStatus;
    /** AI 服务侧的文档 id（解析成功后回写）；未解析/失败时为空。 */
    private String aiDocId;
    /** 最近一次解析成功入库时间。 */
    private LocalDateTime aiIndexedAt;

    @TableLogic
    private Integer deleted;

    /**
     * 对外统一口径：NULL 视同「未解析」。
     *
     * <p>刻意重写 Lombok 生成的 getter 而不是加 {@code @TableField} 默认值：
     * 数据库层不想给存量行做 UPDATE 回填（一次全表更新没必要），
     * 于是把「NULL = NOT_PARSED」这条规则收在实体内部，读侧自动归一。
     */
    public String getAiIndexStatus() {
        return aiIndexStatus == null || aiIndexStatus.isBlank() ? "NOT_PARSED" : aiIndexStatus;
    }
}

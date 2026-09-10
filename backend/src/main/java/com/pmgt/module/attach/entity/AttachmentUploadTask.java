package com.pmgt.module.attach.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 附件的「后台上传」任务记录（上传记录）。
 *
 * <p>大文件同步写对象存储易超时，故上传拆成两步：
 * <ol>
 *   <li>请求阶段：文件先落本地临时区，登记本表（{@link #PENDING}）后<b>立即返回</b>，前端不阻塞；</li>
 *   <li>后台阶段：异步线程推送对象存储，过程中回写 {@link #progress}，结束置
 *       {@link #SUCCESS} 或 {@link #FAILED}。</li>
 * </ol>
 * 成功后会写入正式 {@code attachment} 记录，其 id 存于 {@link #attachmentId}。
 */
@Data
@TableName("attachment_upload_task")
public class AttachmentUploadTask implements Serializable {

    /** 已受理（文件已落临时区，等待后台推送） */
    public static final String PENDING = "PENDING";
    /** 推送存储中 */
    public static final String UPLOADING = "UPLOADING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String bizType;
    private Long bizId;
    private String attachType;
    private String fileName;
    private Long fileSize;
    private String fileExt;
    /** 落库后的存储名（uuid.ext） */
    private String storedName;
    /** 正式相对路径（YYYY/MM/uuid.ext），即 attachment.file_path */
    private String filePath;
    /** 推送期间本地临时文件绝对路径（完成后清理） */
    private String tempPath;
    /** PENDING | UPLOADING | SUCCESS | FAILED */
    private String status;
    /** 推送进度 0~100（估算值，仅用于展示） */
    private Integer progress;
    private String errorMsg;
    /** 成功后关联的 attachment.id */
    private Long attachmentId;
    private Long uploadUserId;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;

    @TableLogic
    private Integer deleted;
}

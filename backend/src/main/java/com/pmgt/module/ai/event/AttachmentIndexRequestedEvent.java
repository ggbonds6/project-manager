package com.pmgt.module.ai.event;

/**
 * 「附件已可读，请（按需）送 AI 解析」事件。
 *
 * <p>为什么用事件而不是让 {@code AttachmentUploadService} 直接调 {@code AiTaskService}：
 * <ol>
 *   <li><b>依赖方向</b>：上传是附件模块的基础能力，AI 解析是可选增强，
 *       直接调用会让 attach 模块反向依赖 ai 模块（以后 aa-backend 不在、或换实现都要改上传代码）；</li>
 *   <li><b>失败隔离的物理保证</b>：监听者做的是「调外部 HTTP + 写库」，
 *       绝不能有任何异常从这条路径回到上传流程；事件是单向的，出问题只可能出在监听者里；</li>
 *   <li><b>触发时机可读</b>：事件的发布点在「附件记录已落库、上传任务已置成功」之后，
 *       读代码的人一眼能看出"什么时候算可读"。</li>
 * </ol>
 *
 * <p>字段刻意只带 id：监听者应当自己去库里读最新状态，
 * 事件里塞快照会在并发场景下变成"用旧数据做决定"。
 */
public class AttachmentIndexRequestedEvent {

    private final Long attachmentId;
    /** 触发人（上传者），仅用于操作留痕；后台线程里 AuthContext 是空的，必须显式带过去。 */
    private final Long operatorUserId;

    public AttachmentIndexRequestedEvent(Long attachmentId, Long operatorUserId) {
        this.attachmentId = attachmentId;
        this.operatorUserId = operatorUserId;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public Long getOperatorUserId() {
        return operatorUserId;
    }
}

package com.pmgt.module.attach.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 附件「当前存储里读不读得到」的批量探测结果（{@code GET /api/attachments/availability}）。
 *
 * <h2>为什么会存在这个接口</h2>
 * <p>库是共享的（开发机连的就是生产崖山库），但 {@code app.storage.type=local} 时附件的字节
 * 只写在**本机磁盘**上——于是在本机用 local 模式上传的附件，到服务器上"文件缺失"
 * （历史事故：412 条孤儿附件，服务器点预览/下载一律 404「附件文件缺失」）。
 * 前端需要提前知道"这条附件在当前存储里能不能读"，才能在列表上给出标识、并把点预览/下载的
 * 交互降级为一句说明，而不是每次都发一个注定 404 的请求。
 *
 * <h2>这是"探测"，不是"校验"</h2>
 * <p>{@code available=false} 只说明**此刻**在**当前配置的存储**里读不到：附件可以被重新上传，
 * 存储的 {@code prefix}/{@code bucket} 也可能被修正。所以调用方不要把它当成附件的固有属性持久化。
 *
 * <h2>字段口径</h2>
 * <ul>
 *   <li>{@code reason} 只有三种通用中文文案（见 {@code AttachmentAvailabilityService}），
 *       <b>不含</b> OBS 端点/AK/SK/对象 key 全名，也不含异常堆栈——接口是给普通用户看的；</li>
 *   <li>{@code size} 来自存储侧元信息（本地文件大小 / OBS 对象长度），取不到就是 {@code null}，
 *       不要回落到 {@code attachment.file_size}（那只是上传时登记的值，正是要验证的对象）。</li>
 * </ul>
 */
@Data
public class AttachmentAvailabilityVO {

    /** 与请求里的 id 顺序一致（去重后）；单条探测失败不影响其它条 */
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {

        private Long id;

        /** 当前存储里能否读到该附件的字节（true ⇒ 预览/下载可用） */
        private boolean available;

        /** 存储侧真实字节数；取不到为 null */
        private Long size;

        /** 不可用时的通用中文原因；可用时为 null */
        private String reason;
    }
}

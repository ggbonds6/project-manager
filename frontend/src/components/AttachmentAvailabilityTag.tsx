/**
 * 附件「当前存储里读不到」的标识 + 提示文案（一处定义，标签与 toast 共用）
 * ----------------------------------------------------------------
 * 背景：库是共享的（开发机连的就是生产崖山库），而 `app.storage.type=local` 时附件的字节
 * 只写在**本机磁盘**上。于是在本机用 local 模式上传的附件，到服务器上必然"文件缺失"
 * （历史事故：412 条孤儿附件，服务器点预览/下载一律 404「附件文件缺失」）。
 *
 * 这里只做两件事：
 *  1. 把**用户能看懂的那句话**收在一个常量里（标签的 Tooltip 与点击提示必须是同一句）；
 *  2. 标签只在 `available === false` 时出现——`undefined`（没探测/探测失败）**一律不显示**，
 *     正常用户不该因为一次探测失败被一个警告标签打扰。
 */
import { Tag, Tooltip } from 'antd';

/** 标签文字（只在确认不可读时出现） */
export const LOCAL_ATTACHMENT_UNREADABLE_LABEL = '本地附件·当前存储不可读';

/** 完整说明：标签 Tooltip 与点预览/下载的提示共用这一句 */
export const LOCAL_ATTACHMENT_UNREADABLE_TIP =
  '该附件的文件不在当前存储（通常是本地测试时用 local 存储上传的），因此在服务器上无法预览/下载。' +
  '请重新上传到正式存储，或删除该测试附件。';

/**
 * 附件不可读标签：`available === false` 才渲染，否则什么都不渲染（不占位、不打扰）。
 */
export default function AttachmentAvailabilityTag({
  available,
  /** 紧凑形态（列表行里与其它标签并排时用） */
  compact = false,
}: {
  available?: boolean | null;
  compact?: boolean;
}) {
  if (available !== false) {
    return null;
  }
  return (
    <Tooltip title={LOCAL_ATTACHMENT_UNREADABLE_TIP}>
      <Tag color="warning" style={compact ? { marginInlineEnd: 0 } : undefined}>
        {LOCAL_ATTACHMENT_UNREADABLE_LABEL}
      </Tag>
    </Tooltip>
  );
}

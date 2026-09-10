package com.pmgt.common.storage;

/**
 * 存储写入进度回调（用于后台上传任务回写进度）。
 * 实现方不保证回调频率，也不保证 final 的 transferred 恰等于 total。
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param transferred 已写入字节数
     * @param total       总字节数（可能为 -1 表示未知）
     */
    void onProgress(long transferred, long total);
}

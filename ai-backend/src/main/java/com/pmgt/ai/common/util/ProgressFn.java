package com.pmgt.ai.common.util;

/**
 * 进度回调：{@code (阶段, 已完成, 总数)}。
 *
 * <p>阶段名与 Python 版一致（{@code render} / {@code ocr} / {@code check} / {@code store}），
 * 因为上传任务的状态标签与进度权重都按它查表（见迁移对照表 §2）。
 */
@FunctionalInterface
public interface ProgressFn {
    void on(String stage, int done, int total);

    /** 不需要进度时用它，省掉一堆 null 判断。 */
    ProgressFn NONE = (stage, done, total) -> {
    };
}

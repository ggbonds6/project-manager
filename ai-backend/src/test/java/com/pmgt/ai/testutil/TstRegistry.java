package com.pmgt.ai.testutil;

import com.pmgt.ai.common.config.AiSettings;
import com.pmgt.ai.common.model.ModelRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 测试专用的 {@link ModelRegistry} 装配：在进程内注册表上建一个 L2，并<b>关掉 H1 顺序守卫</b>。
 *
 * <p>为什么要有这个小工具：L2 的语义（在飞上限、公平排队、幂等重试、繁忙语义、快慢超时）
 * 是<b>独立于 L1</b> 的正确性，这些用例不该被迫先建一个 {@code PipelineGate}；
 * 而 H1 守卫在生产里恒为开启（没有任何配置项能关掉），它的判据由
 * {@code PipelineGateTest} 正面覆盖（"先 L2 再等 L1"必须立刻抛错、"先 L1 后 L2"必须跑通）。
 *
 * <p>⚠️ <b>不要在生产代码里用这个类</b>（它在 {@code src/test} 下，编译期就进不了主代码）。
 */
public final class TstRegistry {

    private TstRegistry() {
    }

    /** 进程内 L2 注册表，H1 守卫关闭。 */
    public static ModelRegistry l2(AiSettings settings) {
        ModelRegistry.setH1GuardEnabled(false);
        return new ModelRegistry(settings, new SimpleMeterRegistry());
    }
}

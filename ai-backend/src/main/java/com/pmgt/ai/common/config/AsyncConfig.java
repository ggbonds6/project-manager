package com.pmgt.ai.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 上传解析任务的线程池。
 *
 * <p>为什么 2~4 个线程就够：解析的瓶颈在平台 OCR 与大模型（都在 GPU 机上、自带并发），
 * 本服务只是编排；线程开多了只会让本地 CPU 渲染 PDF 互相抢核（Python 版实测：
 * 本地渲染并发反而更慢）。
 *
 * <p>为什么不直接用 {@code @Async}：{@code submit()} 内部调用 {@code run()} 属于**自调用**，
 * 走不到 Spring 代理，{@code @Async} 会静默失效（任务变成同步执行、接口又变回超时）。
 * 所以这里显式注入线程池并提交任务——行为一眼可见，不依赖代理魔法。
 */
@Configuration
public class AsyncConfig {

    @Bean("uploadTaskExecutor")
    public ThreadPoolTaskExecutor uploadTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pm-ai-task-");
        // 关闭时等任务跑完（避免"服务停了、任务状态永远停在 PARSING"）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

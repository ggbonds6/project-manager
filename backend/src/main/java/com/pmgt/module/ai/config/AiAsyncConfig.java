package com.pmgt.module.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 解析触发用的后台执行器（与上传线程池分开）。
 *
 * <h2>为什么单独一个池，不复用上传池</h2>
 * 自动解析要做两件"慢"事：读附件文件（最大 500MB）+ 把文件推给 AI 服务。
 * 如果挤在上传池（2~4 线程）里跑，一个 500MB 附件的解析会占满线程，
 * 后面的大文件上传就只能排队——把「AI 增强」的代价转嫁到「基础上传」上，本末倒置。
 *
 * <h2>为什么单线程 + 有界队列 + CallerRunsPolicy</h2>
 * <ul>
 *   <li><b>单线程</b>：自动解析是后台尽力而为的事，不该并发抢带宽/抢 AI 服务算力；
 *       排队反而让每次触发都能拿到较稳定的耗时；</li>
 *   <li><b>有界队列</b>：防止 AI 服务挂掉时任务无限堆积；</li>
 *   <li><b>CallerRunsPolicy</b>：队列满时由提交者执行——提交者是上传池线程，
 *       最多拖慢一个上传任务的收尾，但不会丢触发（且上传响应早已返回，不影响用户）。</li>
 * </ul>
 *
 * <p>默认是非 bean 方法形式注入的接口，便于单测直接传一个「同步执行」的实现，
 * 不必启动 Spring 上下文（见 AttachmentUploadServiceTest）。
 */
@Configuration
public class AiAsyncConfig {

    /** 自动解析触发队列长度：够吸收一次批量上传，又不至于把内存撑起来。 */
    private static final int QUEUE_CAPACITY = 200;

    @Bean(destroyMethod = "shutdown")
    public ExecutorService aiParseExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "ai-auto-parse-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}

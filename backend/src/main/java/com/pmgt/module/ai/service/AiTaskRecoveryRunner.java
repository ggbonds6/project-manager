package com.pmgt.module.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时收起「上次进程留下的」解析任务状态。
 *
 * <p>为什么需要它：解析在 AI 服务侧的后台线程里跑，主系统这一行只是影子记录。
 * 主系统重启后，即使 AI 服务把解析跑完了，也没有人会去更新主系统的状态，
 * 任务会永远停在 RUNNING，前端标签一直转圈。
 * 处理方式是<b>标成失败并写清原因</b>（请重试），与 AI 服务自己重启时
 * 把残留任务标失败的做法一致——比让用户无限等待更诚实。
 *
 * <p>{@code @Order(2)}：在 {@code YashanMigrationRunner}(@Order(1)) 之后跑，
 * 保证 V13 建的表已经存在。
 */
@Component
@Order(2)
public class AiTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRecoveryRunner.class);

    private final AiTaskService taskService;

    public AiTaskRecoveryRunner(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            taskService.markInterruptedAsFailed();
        } catch (Exception e) {
            // 恢复是「尽力而为」：失败了不该阻止应用启动，但要留下痕迹
            log.warn("[ai] 残留解析任务恢复失败（不影响启动）：{}", e.getMessage());
        }
    }
}

package com.pmgt.ai.common.config;

import com.pmgt.ai.module.store.DocStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 少量需要显式装配的 Bean。
 *
 * <p>为什么单独放这里：`DocStore` 是"以根目录为参数"的普通类（便于单测塞临时目录），
 * 不适合自己声明成 `@Component`（那样就得靠 `@Value` 拿目录、单测更难写）。
 * 由配置类按 `ai.work-dir` 装配一次，测试里也能直接 `new DocStore(tempDir)`。
 */
@Configuration
public class BeansConfig {

    @Bean
    public DocStore docStore(AiSettings settings) {
        return DocStore.underWorkDir(settings.getWorkDir());
    }
}

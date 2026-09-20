package com.pmgt.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 能力服务入口。
 *
 * <p>独立于主系统运行（默认 8100）：对外提供附件解析 / 平台 OCR / 大模型抽取问答 / 向量检索；
 * 所有重活（OCR、大模型、向量化、重排）都在内网平台网关侧，本服务只做编排与确定性校验。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}

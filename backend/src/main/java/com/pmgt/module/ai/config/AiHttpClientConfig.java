package com.pmgt.module.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI 服务 HTTP 客户端配置。
 *
 * <p>为什么建两个 {@link RestClient}（而不是一个 + 每次传超时）：
 * Spring 6.1 的 {@code RestClient} 没有「单次请求级超时」API，超时是 request factory 的属性；
 * 而且问答（实测 ~10s，但长问答要留到 180s）与健康探测/列表（必须快速失败）对超时的要求是相反的：
 * 用同一个值要么让健康探测卡 180s，要么让问答在 10s 被腰斩。所以按用途各配一个。
 *
 * <p>超时值走 {@link AiProperties}，不硬编码——内网环境差异大，运维要能不改代码调整。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiHttpClientConfig {

    /** 问答用（长超时）。 */
    public static final String CHAT_REST_CLIENT = "aiChatRestClient";
    /** 其它调用用（短超时）。 */
    public static final String FAST_REST_CLIENT = "aiFastRestClient";

    @Bean(CHAT_REST_CLIENT)
    public RestClient aiChatRestClient(AiProperties props, RestClient.Builder builder) {
        return build(props, builder, Duration.ofSeconds(Math.max(1, props.getChatTimeoutSeconds())));
    }

    @Bean(FAST_REST_CLIENT)
    public RestClient aiFastRestClient(AiProperties props, RestClient.Builder builder) {
        return build(props, builder, Duration.ofSeconds(Math.max(1, props.getTimeoutSeconds())));
    }

    /**
     * 统一构造：基址 + 服务间令牌头 + 超时。
     *
     * <p>令牌头只在配置了非空值时才加：AI 服务当前没有入站鉴权，发一个空 Bearer
     * 只会让它（将来加上鉴权时）误判成「令牌错误」，不如明确不发。
     */
    private RestClient build(AiProperties props, RestClient.Builder builder, Duration timeout) {
        RestClient.Builder b = builder.clone()
                .baseUrl(props.getBaseUrl())
                .requestFactory(requestFactory(timeout));
        if (props.getToken() != null && !props.getToken().isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + props.getToken());
        }
        return b.build();
    }

    private ClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}

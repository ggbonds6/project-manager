package com.pmgt.common.config;

import com.pmgt.common.security.RoleInterceptor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    /** 附件存储类型（local | obs）：仅 local 时注册 /uploads 本地静态映射 */
    @Value("${app.storage.type:local}")
    private String storageType;

    private Path uploadPath;

    private final RoleInterceptor roleInterceptor;

    public WebConfig(RoleInterceptor roleInterceptor) {
        this.roleInterceptor = roleInterceptor;
    }

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建附件目录: " + uploadPath, e);
        }
    }

    public Path getUploadPath() {
        return uploadPath;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发期全放开；生产部署时收紧为同源/网关代理
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 附件访问统一走后端 /api/attachments/{id}/download；此处仅 local 存储时提供 /uploads 静态直读兼容
        // （obs 存储时附件在 OBS 桶，无本地文件，不注册静态映射）
        if (!"obs".equals(storageType)) {
            String location = uploadPath.toUri().toString();
            registry.addResourceHandler("/uploads/**").addResourceLocations(location);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }
}

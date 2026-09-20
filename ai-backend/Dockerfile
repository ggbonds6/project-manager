# AI 能力服务镜像（Java 版）
# build（构建上下文 = 仓库根目录）：docker build -f ai-backend/Dockerfile -t pm-ai-backend .
#
# 为什么独立镜像：平台 OCR / 大模型 / 向量化都在 GPU 机上，AI 能力必须能脱离主系统单独发版。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Maven 镜像加速（与主系统 backend 镜像保持一致；内网私服可替换）
RUN mkdir -p /root/.m2 \
 && printf '%s' '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
COPY ai-backend/pom.xml ./pom.xml
COPY ai-backend/src ./src
# 测试在 CI/本机质量门里跑；镜像构建只打产物，避免构建期依赖外部网络（平台网关在内网，构建机上未必可达）
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre
WORKDIR /app
# 附件名与内容一律 UTF-8（历史踩坑：编码不一致会导致中文附件名/内容乱码）
ENV LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75"
COPY --from=build /app/target/pm-ai-backend-*.jar app.jar
# work/ 里放临时上传文件、文档库、向量缓存：挂卷持久化（都是**可重建物**，丢了不影响正确性）
VOLUME ["/app/work"]
EXPOSE 8100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# 眼互后端镜像（WS2 / ADR-2）：多阶段构建 + 非 root 运行
# 构建：maven 3.9 + JDK17 → 运行：eclipse-temurin 17 JRE
# 用法：docker compose up -d --build（由 docker-compose.yml 编排）

# ===== 构建阶段 =====
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 阿里云 Maven 镜像（国内 ECS 加速，见 settings.xml）
COPY settings.xml /root/.m2/settings.xml
# 先复制 pom 拉依赖，利用层缓存加速迭代构建
COPY pom.xml .
RUN mvn -q dependency:resolve dependency:resolve-plugins -B || true
COPY src ./src
RUN mvn -q -DskipTests package -B

# ===== 运行阶段（非 root：AC8）=====
FROM eclipse-temurin:17-jre
# 固定 uid 10001 的应用账号（volume 权限可预测）
RUN useradd -r -u 10001 -m eyemon
WORKDIR /app
COPY --from=build /build/target/eye-monitor-server-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /data/media /data/avatar && chown -R eyemon:eyemon /data /app
USER eyemon
EXPOSE 8080
# -Dfile.encoding=UTF-8：中文消息不乱码（AGENTS.md 约定；Linux 下同样显式带上）
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
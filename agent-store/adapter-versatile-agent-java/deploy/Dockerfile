# adapter-versatile-agent-java 运行时镜像
#
# 说明：不使用 apt-get（规避国内 ECS 构建常见的 GPG/慢速失败），
# 也不从外部镜像复制 curl（避免拉取 Docker Hub 镜像失败）。
# 健康检查改用基础镜像自带的 bash /dev/tcp 做端口探测，零外部依赖。

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY target/adapter-versatile-agent-java-*.jar app.jar

ENV VERSATILE_AGENT_PORT=8191
EXPOSE 8191

HEALTHCHECK --interval=10s --timeout=5s --retries=8 --start-period=90s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8191' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

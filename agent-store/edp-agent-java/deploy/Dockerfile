# edp-agent-java 运行时镜像
#
# 构建前须先在宿主机完成 Maven 打包（在仓库根目录）：
#   ./mvnw clean install -DskipTests
#   ./mvnw -pl agent-store/edp-agent-java -am package -DskipTests
#
# 镜像组合策略：
#   - 以 python:3.11-slim 为基础镜像：Python 原生可用，用于运行场景 skill 脚本。
#   - 从 eclipse-temurin 复制自包含 JDK：Temurin 的 JDK 自带运行库、对宿主 glibc 要求低，
#     复制到 Debian 基础上可稳定运行；避免"从 Debian-python 复制到 Ubuntu-java"造成的
#     glibc 版本不匹配，也完全不使用 apt-get（规避国内 ECS 构建常见的 GPG/慢速失败）。

FROM eclipse-temurin:21-jre-jammy AS jdk

FROM python:3.11-slim

WORKDIR /app

# 国内服务器构建时加速 pip（海外环境可 docker build --build-arg 覆盖）
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ARG PIP_TRUSTED_HOST=mirrors.aliyun.com
ENV PIP_INDEX_URL=${PIP_INDEX_URL} \
    PIP_TRUSTED_HOST=${PIP_TRUSTED_HOST} \
    PIP_DISABLE_PIP_VERSION_CHECK=1

COPY deploy/requirements-mcp.txt /tmp/requirements-mcp.txt
RUN pip install --no-cache-dir -r /tmp/requirements-mcp.txt

# 自包含 JDK 随镜像携带，无需 apt 安装 java
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY engine/target/edp-agent-engine-*.jar app.jar
COPY engine/src/main/resources/governance/ /app/config/governance/
COPY deploy/config/edp-config.yaml /app/config/edp-config.yaml
COPY scenarios/wealth-demo/ /app/scenarios/wealth-demo/

ENV SERVER_PORT=8190
ENV EDPA_AGENT_CONFIG_PATH=/app/config/edp-config.yaml
ENV EDP_AGENT_SCENARIO_HOME=/app/scenarios/wealth-demo
ENV PYTHONIOENCODING=utf-8

EXPOSE 8190

# 健康检查用 Python 内置 urllib，Python 为原生基础，无需 curl
HEALTHCHECK --interval=10s --timeout=5s --retries=8 --start-period=90s \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8190/.well-known/agent-card.json', timeout=3)"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

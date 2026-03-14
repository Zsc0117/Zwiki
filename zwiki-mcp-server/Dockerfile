FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="ZwikiAI Team"
LABEL description="ZwikiAI MCP Server — 对外暴露仓库阅读、文档检索等核心能力的标准 MCP 协议服务"

WORKDIR /app

COPY target/*.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx256m"
ENV MYSQL_HOST=localhost
ENV MYSQL_PORT=3306
ENV MYSQL_USER=root
ENV MYSQL_PASSWORD=root
ENV ZWIKI_MCP_API_KEYS=""

EXPOSE 9090

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]

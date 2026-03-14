# 部署指南

本文档面向希望将 ZwikiAI 部署到服务器或生产环境的运维人员和开发者。

---

## 部署概览

ZwikiAI 是微服务架构，生产环境推荐的部署拓扑如下：

| 组件 | 实例数 | 说明 |
|------|--------|------|
| **zwiki-gateway** | 1+ | 入口网关，所有请求经过此处 |
| **zwiki-wiki** | 1+ | 核心业务服务 |
| **zwiki-review** | 0-1 | 可选，代码审查 |
| **zwiki-memory** | 0-1 | 可选，向量检索增强 |
| **zwiki-mcp-server** | 0-1 | 可选，IDE MCP 接入 |
| **zwiki-frontend** | 1 | 静态资源，可由 Nginx 托管 |
| **MySQL** | 1 | 所有服务共享同一数据库 |
| **Redis** | 1 | 缓存 / Session / 消息队列 |
| **Nacos** | 0-1 | 可选，配置中心与服务注册 |

> **最小部署**：Gateway + Wiki + Frontend + MySQL + Redis 即可运行核心功能。

---

## 服务器环境要求

| 项目 | 最低配置 | 推荐配置 |
|------|---------|---------|
| CPU | 2 核 | 4 核+ |
| 内存 | 4 GB | 8 GB+ |
| 磁盘 | 20 GB | 50 GB+（含仓库克隆空间） |
| JDK | 21 | Eclipse Temurin 21 |
| OS | Linux（Ubuntu 22.04 / CentOS 8+） | Ubuntu 22.04 LTS |

---

## 构建产物

在开发机或 CI 环境中编译：

```bash
mvn clean package -DskipTests
```

产物位置：

| 服务 | JAR 路径 |
|------|---------|
| Gateway | `zwiki-gateway/target/zwiki-gateway-1.0.0-SNAPSHOT.jar` |
| Wiki | `zwiki-wiki/target/zwiki-wiki-1.0.0-SNAPSHOT.jar` |
| Review | `zwiki-review/target/zwiki-review-1.0.0-SNAPSHOT.jar` |
| Memory | `zwiki-memory/target/zwiki-memory-1.0.0-SNAPSHOT.jar` |
| MCP Server | `zwiki-mcp-server/target/zwiki-mcp-server-1.0.0-SNAPSHOT.jar` |

前端构建：

```bash
cd zwiki-frontend
npm install
npm run build
```

产物在 `zwiki-frontend/build/` 目录，部署时由 Nginx 直接托管。

---

## 中间件部署

### MySQL

```bash
# Docker 方式
docker run -d \
  --name zwiki-mysql \
  --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD=<your-password> \
  -e MYSQL_DATABASE=zwiki \
  -v /data/mysql:/var/lib/mysql \
  -p 3306:3306 \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci

# 初始化表结构
docker exec -i zwiki-mysql mysql -u root -p<your-password> < database-sql/init.sql
```

### Redis

```bash
docker run -d \
  --name zwiki-redis \
  --restart unless-stopped \
  -v /data/redis:/data \
  -p 6379:6379 \
  redis:7-alpine \
  redis-server --requirepass <your-redis-password> --appendonly yes
```

### Nacos（可选）

```bash
docker run -d \
  --name zwiki-nacos \
  --restart unless-stopped \
  -e MODE=standalone \
  -e SPRING_DATASOURCE_PLATFORM=mysql \
  -e MYSQL_SERVICE_HOST=<mysql-host> \
  -e MYSQL_SERVICE_PORT=3306 \
  -e MYSQL_SERVICE_DB_NAME=nacos_config \
  -e MYSQL_SERVICE_USER=root \
  -e MYSQL_SERVICE_PASSWORD=<your-password> \
  -p 8848:8848 \
  -p 9848:9848 \
  nacos/nacos-server:v2.3.0
```

如果使用 Nacos，需要在 Nacos 控制台中创建以下配置文件（参考 `database-sql/` 下的模板）：

- `zwiki-wiki-service-dev.yaml`
- `zwiki-review-service-dev.yaml`
- `zwiki-memory-service-dev.yaml`
- `zwiki-gateway-service-dev.yaml`

> 不使用 Nacos 时，各服务自动回退到本地 `application.yml` 中的配置。
>
> 当前仓库**暂不提供**官方 `docker-compose.yml`，请优先使用本文档中的手动部署方式，或基于这些服务与环境变量自行编排。

---

## 环境变量配置

生产环境推荐通过环境变量覆盖敏感配置，不要直接修改 JAR 内的 `application.yml`。

### 必须配置

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | LLM API Key（至少一个） |
| `ZWIKI_REDIS_PASSWORD` | Redis 密码 |

### 推荐配置

| 变量 | 说明 |
|------|------|
| `GITHUB_CLIENT_ID` | GitHub OAuth Client ID |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth Client Secret |
| `GITEE_CLIENT_ID` | Gitee OAuth Client ID |
| `GITEE_CLIENT_SECRET` | Gitee OAuth Client Secret |
| `GITHUB_TOKEN` | GitHub PAT（代码审查用） |
| `GITHUB_WEBHOOK_SECRET` | Webhook 签名密钥 |
| `ZWIKI_MCP_API_KEYS` | MCP Server 的 API Key |

### 网络相关

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NACOS_SERVER_ADDR` | `127.0.0.1:8848` | Nacos 地址 |
| `ZWIKI_REDIS_HOST` | `127.0.0.1` | Redis 地址 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | 前端域名（生产环境改为实际域名） |
| `PROJECT_REPO_PATH` | `./data/repositories` | 仓库克隆存储路径 |

完整环境变量列表参考根目录 `.env.example`。

---

## 启动 Java 服务

### 直接启动

```bash
# 网关
nohup java -jar zwiki-gateway-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  > gateway.log 2>&1 &

# Wiki 核心服务
nohup java -jar zwiki-wiki-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  > wiki.log 2>&1 &

# 代码审查（可选）
nohup java -jar zwiki-review-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  > review.log 2>&1 &

# 记忆服务（可选）
nohup java -jar zwiki-memory-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  > memory.log 2>&1 &

# MCP Server（可选）
nohup java -jar zwiki-mcp-server-1.0.0-SNAPSHOT.jar \
  > mcp-server.log 2>&1 &
```

### Systemd 服务（推荐）

以 `zwiki-wiki` 为例，创建 `/etc/systemd/system/zwiki-wiki.service`：

```ini
[Unit]
Description=ZwikiAI Wiki Service
After=network.target mysql.service redis.service

[Service]
User=zwiki
Type=simple
ExecStart=/usr/bin/java -Xmx1g -jar /opt/zwiki/zwiki-wiki-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
WorkingDirectory=/opt/zwiki
EnvironmentFile=/opt/zwiki/.env
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/zwiki/wiki.log
StandardError=append:/var/log/zwiki/wiki-error.log

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable zwiki-wiki
sudo systemctl start zwiki-wiki
```

其他服务同理，复制修改 JAR 路径和服务名即可。

---

## Nginx 配置

前端静态资源和 API 反向代理推荐由 Nginx 统一处理。

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态资源
    location / {
        root /opt/zwiki/frontend/build;
        index index.html;
        try_files $uri $uri/ /index.html;
    }

    # API 反向代理到网关
    location /api/ {
        proxy_pass http://127.0.0.1:8992;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE 支持
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 600s;
    }

    # OAuth 回调
    location /login/oauth2/ {
        proxy_pass http://127.0.0.1:8992;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /oauth2/ {
        proxy_pass http://127.0.0.1:8992;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # WebSocket 通知推送
    location /ws/ {
        proxy_pass http://127.0.0.1:8992;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }

    # MCP Server（如果需要外部访问）
    location /sse {
        proxy_pass http://127.0.0.1:9090;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400;
        proxy_set_header Host $host;
    }
}
```

### HTTPS（推荐）

生产环境强烈建议启用 HTTPS。可以使用 Let's Encrypt + Certbot：

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

启用 HTTPS 后，需要同步修改：

- 环境变量 `CORS_ALLOWED_ORIGINS` 改为 `https://your-domain.com`
- OAuth App 的回调地址改为 HTTPS

---

## 健康检查

各服务启动后可通过以下方式确认运行状态：

| 服务 | 健康检查 |
|------|---------|
| Gateway | `curl http://localhost:8992/actuator/health` |
| Wiki | `curl http://localhost:8991/actuator/health` |
| Review | `curl http://localhost:8990/actuator/health` |
| Memory | `curl http://localhost:8993/api/health` |
| MCP Server | `curl http://localhost:9090/health` |
| Frontend | 浏览器访问 `http://localhost:3000` |

---

## 数据备份

### MySQL

```bash
mysqldump -u root -p zwiki > backup_$(date +%Y%m%d).sql
```

### 仓库克隆数据

仓库克隆数据存储在 `PROJECT_REPO_PATH` 指定的目录（默认 `./data/repositories`）。建议定期备份或确保磁盘空间充足。

---

## 安全加固

- 所有密钥、密码通过环境变量传入，不要写入配置文件或代码
- MySQL 和 Redis 不要暴露到公网，限制为内网访问
- 使用 HTTPS 并配置 HSTS
- 定期更新依赖和 JDK 版本
- 如果使用 Nacos，建议启用认证并限制访问

---

## 常见问题

### 服务启动后前端访问白屏

- 检查 Nginx 配置中 `try_files` 是否正确
- 确认前端 build 产物已部署到正确路径

### SSE 流式输出中断

- 确认 Nginx 配置中 `proxy_buffering off` 已设置
- 确认 `proxy_read_timeout` 足够长（推荐 600s）

### OAuth 登录后 Cookie 丢失

- 确认 CORS 配置的域名与实际访问域名一致
- 确认 Nginx 正确传递了 `X-Forwarded-Proto` 头

### 仓库克隆失败

- 确认服务器可以访问 GitHub / Gitee
- 如果需要代理，配置 `GIT_PROXY_ENABLED=true` 及相关代理参数

---

## 下一步

- 查看 [本地开发指南](LOCAL_DEV.md) 了解开发环境搭建
- 查看 [GitHub Webhook 配置指南](GitHub-Webhook配置指南.md) 了解代码审查接入
- 查看根目录 [README](../README.md) 了解项目全貌

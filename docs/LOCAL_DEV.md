# 本地开发指南

本文档面向希望在本地环境运行和开发 ZwikiAI 的开发者，覆盖从环境准备到各服务启动的全流程。

---

## 环境准备

### 必备软件

| 软件 | 最低版本 | 安装建议 |
|------|---------|---------|
| **JDK** | 21 | 推荐 [Eclipse Temurin](https://adoptium.net/) 或 GraalVM |
| **Maven** | 3.9+ | 建议使用 [SDKMAN](https://sdkman.io/) 管理 |
| **Node.js** | 16+ | 推荐 LTS 版本，建议使用 nvm 管理 |
| **MySQL** | 8.0 | 本地安装或使用 Docker |
| **Redis** | 6.0+ | 本地安装或使用 Docker |
| **Git** | 2.x | 用于仓库克隆功能 |

### 可选软件

| 软件 | 用途 | 说明 |
|------|------|------|
| **Nacos** | 配置中心 / 服务注册 | 不启用时各服务自动使用本地 `application.yml` |
| **MinIO** | 对象存储 | 涉及媒体资源导出时需要 |
| **Docker** | 运行 MySQL / Redis 等中间件 | 可选，但推荐用于快速启动中间件 |

---

## 快速启动中间件（Docker 方式）

如果你本地没有安装 MySQL 和 Redis，可以用 Docker 快速启动：

```bash
# MySQL
docker run -d \
  --name zwiki-mysql \
  -e MYSQL_ROOT_PASSWORD=your-password \
  -e MYSQL_DATABASE=zwiki \
  -p 3306:3306 \
  mysql:8.0

# Redis
docker run -d \
  --name zwiki-redis \
  -p 6379:6379 \
  redis:7-alpine
```

---

## 数据库初始化

```bash
mysql -u root -p < database-sql/init.sql
```

这会创建 `zwiki` 数据库、所有业务表，并插入初始 LLM 模型配置数据。

如果你使用 Docker 启动的 MySQL：

```bash
docker exec -i zwiki-mysql mysql -u root -pyour-password < database-sql/init.sql
```

---

## 环境变量配置

复制根目录的 `.env.example` 到 `.env`，根据你的本地环境填入真实值：

```bash
cp .env.example .env
```

**最小必填项**（不配置则使用 `application.yml` 中的默认占位值）：

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 至少一个 LLM Provider 的 API Key |

**可选但推荐**：

| 变量 | 说明 |
|------|------|
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth 登录 |
| `GITEE_CLIENT_ID` / `GITEE_CLIENT_SECRET` | Gitee OAuth 登录 |

> 你也可以不设置环境变量，而是直接修改各服务的 `application.yml` 中对应占位符。

---

## 后端编译

```bash
# 在项目根目录执行
mvn clean install -DskipTests
```

如果你的网络访问 Maven Central 较慢，`pom.xml` 已配置了阿里云镜像仓库。

---

## 启动服务

### 最小启动（核心功能）

只需要启动以下两个 Java 服务 + 前端：

```bash
# 终端 1：网关
java -jar zwiki-gateway/target/zwiki-gateway-1.0.0-SNAPSHOT.jar

# 终端 2：Wiki 核心服务
java -jar zwiki-wiki/target/zwiki-wiki-1.0.0-SNAPSHOT.jar

# 终端 3：前端
cd zwiki-frontend && npm install && npm start
```

这套组合可以使用：

- Wiki 文档生成
- AI 对话 / Ask AI
- OAuth 登录
- 个人中心管理
- 代码浏览器
- Draw.io 绘图

### 完整启动（全功能）

在最小启动基础上，追加以下服务：

```bash
# 终端 4：代码审查（需要 GitHub Webhook 配置）
java -jar zwiki-review/target/zwiki-review-1.0.0-SNAPSHOT.jar

# 终端 5：记忆服务（需要 Mem0 / 向量服务）
java -jar zwiki-memory/target/zwiki-memory-1.0.0-SNAPSHOT.jar

# 终端 6：MCP Server（面向 IDE 客户端）
java -jar zwiki-mcp-server/target/zwiki-mcp-server-1.0.0-SNAPSHOT.jar
```

---

## IDE 开发

### IntelliJ IDEA

1. 打开项目根目录作为 Maven 项目
2. 确保 Project SDK 设置为 JDK 21
3. 各服务的启动类：
   - `zwiki-gateway` → `ZwikiGatewayApplication`
   - `zwiki-wiki` → `ZwikiRepositoryWikiApplication`
   - `zwiki-review` → `ZwikiReviewerApplication`
   - `zwiki-memory` → 独立 Python/Java 服务
   - `zwiki-mcp-server` → `ZwikiMcpServerApplication`
4. 建议使用 IDEA 的 Run Configuration 分别配置各服务，便于一键启动

### VS Code

1. 安装 Java Extension Pack
2. 打开项目根目录
3. 使用 `launch.json` 配置各服务启动

### 前端开发

```bash
cd zwiki-frontend
npm install
npm start
```

前端运行在 `http://localhost:3000`，通过 `setupProxy.js` 将 API 请求代理到网关 `http://localhost:8992`。

修改代理目标：

```bash
# 通过环境变量覆盖
API_PROXY_TARGET=http://localhost:8992 npm start
```

---

## 服务端口速查

| 服务 | 端口 | 必须 |
|------|------|------|
| Frontend | 3000 | 是 |
| Gateway | 8992 | 是 |
| Wiki Service | 8991 | 是 |
| Review Service | 8990 | 否 |
| Memory Service | 8993 | 否 |
| MCP Server | 9090 | 否 |

---

## 常见问题

### Maven 编译失败

- 确认 JDK 版本为 21（`java -version`）
- 确认 Maven 版本 ≥ 3.9（`mvn -version`）
- 尝试清理后重新编译：`mvn clean install -DskipTests -U`

### 前端 npm install 失败

- 确认 Node.js 版本 ≥ 16
- 尝试清理缓存：`npm cache clean --force`
- 如果网络慢，设置淘宝镜像：`npm config set registry https://registry.npmmirror.com`

### 服务启动后无法连接数据库

- 确认 MySQL 已启动且 `zwiki` 数据库已创建
- 确认 `application.yml` 中的数据库连接地址、用户名、密码正确
- 如果使用 Docker 启动的 MySQL，确认端口映射正确

### OAuth 登录跳转失败

- 确认 OAuth App 的回调地址配置正确
- GitHub 回调地址：`http://localhost:8991/login/oauth2/code/github`
- Gitee 回调地址：`http://localhost:8991/login/oauth2/code/gitee`

### 前端请求返回 502

- 确认后端网关服务（8992）和 Wiki 服务（8991）已启动
- 检查 `setupProxy.js` 中的代理目标是否正确

---

## 下一步

- 查看 [部署指南](DEPLOYMENT.md) 了解生产环境部署
- 查看 [GitHub Webhook 配置指南](GitHub-Webhook配置指南.md) 了解代码审查接入
- 查看 [MCP Server 文档](../zwiki-mcp-server/README.md) 了解 IDE 接入

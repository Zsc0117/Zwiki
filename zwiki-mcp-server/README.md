# ZwikiAI MCP Server

将 ZwikiAI 的仓库阅读、文档检索、代码浏览等核心能力以标准 [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) 协议对外暴露，供 **Cursor / Windsurf / Claude Code** 等 AI IDE 客户端直接调用。

## 功能概览

| 工具名 | 说明 |
|--------|------|
| `list_projects` | 列出所有已分析的项目，返回 taskId、项目名、仓库地址、状态 |
| `search_wiki` | 在 Wiki 文档的标题和内容中搜索关键词，支持按项目过滤 |
| `get_repo_structure` | 查看项目文件目录结构，支持指定子目录 |
| `read_file` | 读取项目中的单个源文件内容 |
| `get_wiki_document` | 获取 AI 生成的 Wiki 文档内容（目录概览或具体章节） |
| `get_project_report` | 获取项目分析摘要（概述、技术栈、核心模块） |

## 快速开始

### 1. 本地运行

```bash
# 构建
cd zwiki-mcp-server
mvn clean package -DskipTests

# 运行（需要 MySQL 连接，使用与 zwiki-wiki 相同的数据库）
java -jar target/zwiki-mcp-server-1.0.0-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:mysql://localhost:3306/zwiki \
  --zwiki.mcp-server.api-keys=your_secret_key
```

### 2. Docker 运行

```bash
docker build -t zwiki-mcp-server .
docker run -d \
  -p 9090:9090 \
  -e MYSQL_HOST=your_mysql_host \
  -e MYSQL_USER=root \
  -e MYSQL_PASSWORD=root \
  -e ZWIKI_MCP_API_KEYS=your_secret_key \
  zwiki-mcp-server
```

### 3. 环境变量

建议通过环境变量提供数据库连接与 API Key，而不是把敏感信息直接写入配置文件：

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_USER=root
export MYSQL_PASSWORD=your_mysql_password
export ZWIKI_MCP_API_KEYS=your_secret_key
```

## 客户端配置

### Cursor / Windsurf

在 IDE 的 MCP 配置文件中添加：

```json
{
  "mcpServers": {
    "zwiki": {
      "type": "streamableHttp",
      "url": "http://your-server:9090/mcp",
      "headers": {
        "Authorization": "Bearer your_secret_key"
      }
    }
  }
}
```

### Claude Code

```bash
claude mcp add -s user -t http zwiki http://your-server:9090/mcp \
  --header "Authorization: Bearer your_secret_key"
```

### SSE 模式

如果客户端支持 SSE 模式：

```
URL: http://your-server:9090/sse?Authorization=your_secret_key
```

## 使用示例

在 IDE 中对 AI 说：

- "使用 zwiki 列出所有已分析的项目"
- "用 zwiki 搜索关于用户认证的文档"
- "通过 zwiki 查看 Zwiki 项目的文件结构"
- "用 zwiki 读取 ChatController.java 的源代码"
- "用 zwiki 获取项目的 Wiki 文档目录"
- "通过 zwiki 获取项目分析报告"

## 认证

- 通过 `Authorization: Bearer <key>` Header 传递 API Key
- 或通过 URL 参数 `?Authorization=<key>` 传递
- API Key 在 `application.yml` 的 `zwiki.mcp-server.api-keys` 中配置（逗号分隔多个）
- 未配置任何 key 时，认证将被跳过（开发模式）

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | 9090 | 服务端口 |
| `zwiki.mcp-server.api-keys` | 空 | API Key 列表（逗号分隔） |
| `zwiki.mcp-server.max-file-size` | 1048576 (1MB) | 单文件最大读取字节数 |
| `zwiki.mcp-server.max-tree-depth` | 10 | 文件树最大遍历深度 |
| `zwiki.mcp-server.max-search-results` | 20 | 搜索结果最大条数 |

## 架构说明

- **独立微服务**：不依赖 zwiki-wiki 运行，只需连接同一个 MySQL 数据库
- **只读服务**：仅读取数据，不触发分析任务
- **标准 MCP 协议**：手动实现 JSON-RPC 2.0 over HTTP/SSE，完全兼容 MCP 规范 (protocolVersion: 2024-11-05)
- **双协议支持**：SSE (`GET /sse` + `POST /sse/message`) 和 Streamable HTTP (`POST /mcp`)
- **轻量级**：无需 LLM、Redis、Nacos 等依赖，仅 Spring Boot Web + JPA + MySQL

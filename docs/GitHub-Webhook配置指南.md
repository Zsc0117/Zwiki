# GitHub Webhook 智能代码审查 —— 配置指南

> 本指南帮助你完成 Zwiki 智能代码审查模块与 GitHub 的对接。
> 完成后，当目标仓库有 Pull Request 被创建或更新时，系统会自动触发 AI 代码审查并将结果以评论形式发布到 PR 上。
>
> **v2 更新**：系统已支持**多用户配置**，每个用户可通过前端「代码审查」页面管理自己的仓库审查配置，无需修改服务端配置文件。

---

## 系统架构概览

```
GitHub PR Event
    ↓ (Webhook POST)
Gateway (8992) → /api/webhook/github
    ↓
Review Service (8990) → GitHubWebhookController
    ↓ 1. 从payload提取repo_full_name
    ↓ 2. 查DB: zwiki_github_review_config → 获取per-user secret & token
    ↓ 3. HMAC-SHA256签名验证 (per-user secret 或 全局secret)
    ↓ (异步)
GithubWebhookServiceImpl
    ↓ 4. 解析token优先级: 用户自定义PAT > OAuth Token > 全局Token
    ↓
CodeReviewServiceImpl (带per-user token)
    ↓
  ┌─────────────────────────────────┐
  │  多Agent Graph工作流 (优先)      │
  │  Coordinator → Triage → Router  │
  │    → Style/Logic/Security Agent │
  │    → ReportSynthesizer          │
  ├─────────────────────────────────┤
  │  传统LLM工作流 (降级)           │
  │  RAG上下文检索 → LlmAdapter     │
  └─────────────────────────────────┘
    ↓
ResultPublishService → GitHub PR Comments (使用per-user token)
```

---

## 用户自助配置（推荐）

系统已支持通过前端页面进行自助配置，**无需手动修改服务端配置文件**。

### 前提条件
- 已通过 GitHub OAuth 登录 Zwiki 系统

### 操作步骤

1. **进入配置页面**
   - 登录后，点击左侧菜单 **「代码审查」**（个人中心）
   
2. **添加仓库**
   - 点击 **「添加仓库」** 按钮
   - 从下拉列表中选择你的 GitHub 仓库（系统会自动列出你有权限的仓库）
   - 也可以直接输入 `owner/repo` 格式的仓库全名

3. **配置 Webhook Secret（可选但推荐）**
   - 填写一个自定义密钥字符串，如 `my-review-secret-2026`
   - 此密钥需要与 GitHub 仓库 Webhook 设置中的 Secret 一致（见下方「配置 GitHub Webhook」）

4. **配置自定义 PAT（可选）**
   - 默认使用 GitHub OAuth 登录时的 Token
   - 如果 OAuth Token 权限不足（如需要访问私有仓库的代码），可在此填写 Fine-grained PAT
   - 创建 PAT 的方法见下方「创建 GitHub PAT」章节

5. **配置 GitHub Webhook**
   - 页面顶部会显示 **Webhook 回调地址**，点击复制
   - 到 GitHub 仓库 → Settings → Webhooks → Add webhook
   - 填写 Payload URL（刚才复制的地址）、Content type 选 `application/json`、Secret 填与上一步一致的密钥
   - 勾选 **Pull requests** 事件，点击 Add webhook

6. **完成**
   - 创建一个 PR 进行测试
   - 在配置页面可查看审查次数和最后审查时间

### Token 优先级

| 优先级 | Token 来源 | 说明 |
|--------|-----------|------|
| 1（最高） | 自定义 PAT | 用户在配置页面填写的 Fine-grained PAT |
| 2 | OAuth Token | GitHub OAuth 登录时自动获取的 Token |
| 3（最低） | 全局 Token | 服务端 `app.github.token` 配置（管理员设置） |

---

## 附录A：创建 GitHub Personal Access Token (PAT)

### 1.1 打开 GitHub Token 页面

1. 登录 GitHub：https://github.com
2. 点击右上角头像 → **Settings**
3. 左侧栏最下方 → **Developer settings**
4. 左侧栏 → **Personal access tokens** → **Fine-grained tokens**
5. 点击 **Generate new token**

### 1.2 填写 Token 信息

| 字段 | 填写内容 |
|------|----------|
| **Token name** | `zwiki-code-review` （自定义名称） |
| **Expiration** | 建议选 90 days 或 Custom（按需设置） |
| **Repository access** | 选 **Only select repositories**，然后选择你要审查的仓库 |

### 1.3 设置权限（Permissions）

在 **Repository permissions** 下，设置以下权限：

| 权限 | 级别 | 用途 |
|------|------|------|
| **Contents** | Read | 读取仓库代码和 diff |
| **Pull requests** | Read and write | 读取 PR 信息、发布审查评论 |
| **Metadata** | Read（默认已选） | 基础元数据 |

> 其他权限保持默认即可，无需勾选。

### 1.4 生成并保存 Token

1. 点击 **Generate token**
2. **立即复制 Token 值**（格式如 `github_pat_xxxxxx`），关闭页面后无法再查看
3. 将 Token 保存到安全位置

---

## 第二步：配置 GitHub Webhook

### 2.1 进入 Webhook 设置

1. 打开你要审查的 GitHub 仓库页面
2. 点击 **Settings** 标签页
3. 左侧栏 → **Webhooks**
4. 点击 **Add webhook**

### 2.2 填写 Webhook 信息

| 字段 | 填写内容 | 说明 |
|------|----------|------|
| **Payload URL** | `http://<你的服务器IP>:8992/api/webhook/github` | Gateway 端口 8992，路由到 Review 服务 |
| **Content type** | `application/json` | **必须选这个**，不要选 form-urlencoded |
| **Secret** | 自定义字符串，如 `my-zwiki-webhook-secret-2026` | 用于签名验证，需与服务端配置一致 |

### 2.3 选择触发事件

选择 **Let me select individual events**，然后：

- [x] **Pull requests** ← 必须勾选
- [ ] 其他事件取消勾选（减少不必要的请求）

### 2.4 确认创建

1. 确保 **Active** 复选框已勾选
2. 点击 **Add webhook**
3. GitHub 会发送一个 `ping` 事件测试连通性

> **注意**：如果服务器在内网，需要配置内网穿透（如 ngrok/frp），或在服务器有公网 IP 时直接使用。

---

## 第三步：配置服务端

你可以选择 **方式A（推荐）** 或 **方式B** 之一来配置。

### 方式 A：通过 Nacos 配置中心（推荐）

1. 打开你自己的 Nacos 控制台，例如：`http://127.0.0.1:8848/nacos`
2. 使用你本地或部署环境中的 Nacos 账号登录
3. 进入 **配置管理** → **配置列表**
4. 选择你当前部署环境使用的命名空间
5. 找到并编辑 `zwiki-review-service-dev.yaml`
6. 修改以下内容：

```yaml
app:
  github:
    token: github_pat_xxxxxx          # ← 替换为第一步生成的 PAT
    webhook:
      secret: my-zwiki-webhook-secret-2026  # ← 替换为第二步填写的 Secret
```

7. 点击 **发布**

> 发布后 Review 服务会自动热更新配置（约 5-10 秒生效），无需重启。

### 方式 B：通过环境变量

如果不使用 Nacos，可以通过环境变量配置：

**Linux/Mac:**
```bash
export GITHUB_TOKEN=github_pat_xxxxxx
export GITHUB_WEBHOOK_SECRET=my-zwiki-webhook-secret-2026
```

**Windows PowerShell:**
```powershell
$env:GITHUB_TOKEN = "github_pat_xxxxxx"
$env:GITHUB_WEBHOOK_SECRET = "my-zwiki-webhook-secret-2026"
```

**自定义 Docker Compose（仓库当前未提供官方 `docker-compose.yml`）:**
```yaml
services:
  zwiki-review:
    environment:
      - GITHUB_TOKEN=github_pat_xxxxxx
      - GITHUB_WEBHOOK_SECRET=my-zwiki-webhook-secret-2026
```

然后重启 Review 服务。

---

## 第四步：验证配置

### 4.1 检查服务健康状态

```bash
# 直接访问 Review 服务
curl http://localhost:8990/api/webhook/health
# 预期返回: OK

# 通过 Gateway 访问
curl http://localhost:8992/api/webhook/health
# 预期返回: OK
```

### 4.2 检查 GitHub Webhook 投递

1. 回到 GitHub 仓库 → Settings → Webhooks
2. 点击你创建的 Webhook
3. 拉到下方 **Recent Deliveries** 标签页
4. 检查最近一次 `ping` 事件：
   - ✅ 绿色勾号 → 连通正常
   - ❌ 红色叉号 → 查看 Response 排查问题

### 4.3 触发一次测试审查

1. 在目标仓库创建一个测试分支
2. 做一些代码变更并推送
3. 创建一个 Pull Request
4. 观察：
   - Review 服务日志：`./logs/zwiki-review.log` 应出现 `Processing PR review` 日志
   - PR 页面：几秒到几十秒后，会自动出现 AI 审查评论

---

## 端口对照表

| 服务 | 端口 | 用途 |
|------|------|------|
| **Gateway** | 8992 | 统一入口，GitHub Webhook URL 指向此端口 |
| **Review Service** | 8990 | 代码审查服务 |
| **Wiki Service** | 8991 | 主业务服务 |
| **Memory Service** | 8993 | 记忆/向量检索服务 |

---

## 常见问题

### Q: Webhook 返回 401 Invalid signature
**原因**：服务端 `app.github.webhook.secret` 与 GitHub Webhook 设置的 Secret 不一致。
**解决**：确保两边的 Secret 字符串完全相同（注意空格和大小写）。

### Q: Webhook 返回 502 Bad Gateway
**原因**：Gateway 无法连接到 Review 服务。
**解决**：确认 Review 服务已启动在 8990 端口：`curl http://localhost:8990/api/webhook/health`

### Q: PR 没有收到审查评论
**排查步骤**：
1. 检查 GitHub Webhook 是否成功投递（Recent Deliveries）
2. 检查 Review 服务日志是否有 `Processing PR review` 日志
3. 检查 `app.github.token` 是否有效且有足够权限
4. 检查 AI 服务（DashScope）是否正常可用

### Q: GitHub Token 过期了怎么办
1. 重新按第一步创建新 Token
2. 按第三步更新 Nacos 或环境变量中的 `app.github.token`
3. 无需重启服务（Nacos 自动刷新）

### Q: 服务器在内网，GitHub 无法访问
使用内网穿透工具：
- **ngrok**: `ngrok http 8992`，将生成的公网 URL 填入 Webhook Payload URL
- **frp**: 配置 frpc 将 8992 端口映射到公网

### Q: 审查结果不准确
- 确认 Memory 服务（8993）正常运行，RAG 上下文检索依赖此服务
- 检查 DashScope API Key 是否有效
- 查看日志中 LLM 调用是否有报错

# Security Policy

## Supported Versions

当前仓库以主分支最新代码为主进行维护。

## Reporting a Vulnerability

如果你发现安全漏洞，请不要直接在公开 Issue 中披露细节。

建议按以下方式处理：

1. 准备最小复现说明
2. 描述影响范围与风险级别
3. 提供修复建议或临时缓解方案
4. 通过私密渠道联系维护者

如果你暂时没有私密联系方式，可以先提交一个不包含漏洞细节的 Issue，说明需要安全沟通渠道。

## Sensitive Information Rules

请勿在以下位置提交真实敏感信息：

- `application.yml`
- `.env`
- `mcp.json`
- Nacos 配置模板
- Issue / PR 描述
- 截图或日志

敏感信息包括但不限于：

- API Key
- Access Token
- OAuth Client Secret
- Webhook Secret
- SMTP 授权码
- 数据库密码
- 对象存储凭证

## Responsible Disclosure

在漏洞修复发布前，请避免公开：

- 详细利用方式
- 可直接复现的攻击样例
- 尚未轮换的密钥信息

## Supply Chain & Dependencies

如发现第三方依赖存在高危漏洞，欢迎提交修复 PR 或依赖升级建议。

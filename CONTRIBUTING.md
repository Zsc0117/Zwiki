# Contributing to ZwikiAI

感谢你愿意为 ZwikiAI 做出贡献。

## 开始之前

在提交代码前，请先阅读以下内容：

- 根目录 `README.md`
- 根目录 `.env.example`
- `SECURITY.md`

## 提交流程

1. Fork 本仓库
2. 创建功能分支
3. 完成开发与自测
4. 提交 Pull Request

建议分支命名：

- `feature/xxx`
- `fix/xxx`
- `docs/xxx`
- `refactor/xxx`

## 开发建议

- 优先提交小而清晰的改动
- 避免一次性混入无关重构
- 不要提交真实密钥、密码、Token、Webhook Secret
- 不要提交 `logs/`、`build/`、`node_modules/`、`target/` 等产物
- 如果修改接口、配置或部署方式，请同步更新文档

## 代码风格

### 后端

- 使用 Java 21
- 保持 Spring Boot / Spring Cloud 风格一致
- 优先保持现有目录结构和模块边界
- 新增配置项时优先使用环境变量占位

### 前端

- 使用 React 18 + Ant Design
- 保持路由、页面、组件命名一致
- 避免提交未使用的页面、接口或调试代码

## Pull Request 要求

提交 PR 时请尽量说明：

- 修改背景
- 核心改动
- 影响范围
- 是否涉及数据库或配置变更
- 是否需要补充文档

## 提交前检查

建议在提交前确认：

- 项目可正常启动或至少核心模块可运行
- 文档与代码一致
- 未引入敏感信息
- 未提交构建产物和日志文件

## 问题反馈

如果你发现 Bug、文档问题或改进点，请优先通过 Issue 讨论，再提交实现。

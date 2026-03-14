# ZwikiAI Frontend

一个基于 React 的代码仓库分析和管理前端应用，帮助开发者深度理解和管理代码仓库。

## 🚀 功能特性

- **仓库分析**: 深度分析 GitHub 仓库结构和内容
- **智能目录**: 自动生成项目文档目录树
- **Markdown 渲染**: 支持 GitHub 风格的 Markdown 渲染
- **Mermaid 图表**: 支持流程图、时序图等多种图表类型
- **黑暗模式**: 完整的明暗主题切换支持
- **响应式设计**: 适配各种屏幕尺寸
- **任务管理**: 完整的任务创建、编辑、管理功能

## 🛠️ 技术栈

- **前端框架**: React 18
- **UI 组件库**: Ant Design 5.x
- **路由管理**: React Router v6
- **状态管理**: React Hooks
- **动画效果**: Framer Motion
- **Markdown 渲染**: ReactMarkdown + remark-gfm + rehype-highlight
- **图表渲染**: Mermaid
- **代码高亮**: highlight.js
- **构建工具**: Create React App

## 📦 安装和运行

### 环境要求

- Node.js >= 16.0.0
- npm >= 8.0.0

### 安装依赖

```bash
npm install
```

### 启动开发服务器

```bash
npm start
```

应用将在 [http://localhost:3000](http://localhost:3000) 启动。

### 构建生产版本

```bash
npm run build
```

构建文件将输出到 `build` 目录。

## 📁 项目结构

```
src/
├── api/                    # API 接口
│   └── task.js            # 任务相关 API
├── components/            # 通用组件
│   ├── AddRepoModal.jsx   # 添加仓库弹窗
│   ├── ComingSoon.jsx     # 占位页面
│   ├── HeaderNav.jsx      # 顶部导航
│   ├── MermaidChart.jsx   # Mermaid 图表组件
│   └── PageLoading.jsx    # 页面加载组件
├── layouts/               # 布局组件
│   ├── BasicLayout.jsx    # 基础布局
│   ├── MainLayout.jsx     # 管理布局兼容层
│   └── UserCenterLayout.jsx # 个人中心布局
├── pages/                 # 页面组件
│   ├── HomePage.jsx       # 首页
│   ├── RepoDetail.jsx     # 仓库详情页
│   ├── TaskCreate.jsx     # 任务创建页
│   ├── TaskDetail.jsx     # 任务详情页
│   ├── TaskEdit.jsx       # 任务编辑页
│   └── TaskList.jsx       # 任务列表页
├── theme/                 # 主题配置
│   └── themeConfig.js     # Ant Design 主题配置
├── utils/                 # 工具函数
│   └── dateFormat.js      # 日期格式化
├── App.jsx               # 应用主组件
├── App.css               # 全局样式
└── index.js              # 应用入口
```

## 主要功能

### 首页
- 搜索和浏览已分析的代码仓库
- 添加新的 GitHub 仓库进行分析
- 响应式卡片布局展示仓库信息

### 仓库详情页
- 左侧目录树展示项目结构
- 右侧内容区域显示文档详情
- 支持 Markdown 渲染和代码高亮
- 支持 Mermaid 图表渲染

### 个人中心
- 任务列表管理
- 任务详情查看
- 任务创建和编辑
- 系统状态监控

### 主题系统
- 完整的明暗主题切换
- 所有组件都支持主题适配
- 平滑的主题切换动画

## 配置说明

### API 配置

开发环境默认通过 `src/setupProxy.js` 代理到网关服务：

```javascript
const target = process.env.API_PROXY_TARGET || process.env.REACT_APP_API_PROXY_TARGET || 'http://localhost:8992';
```

### 主题配置

在 `src/theme/themeConfig.js` 中自定义 Ant Design 主题：

```javascript
const themeConfig = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 6,
    // 更多配置...
  }
};
```

## 🚀 部署

### 使用 Nginx

1. 构建项目：
```bash
npm run build
```

2. 将 `build` 目录内容部署到 Nginx 服务器

3. 配置 Nginx：
```nginx
server {
    listen 80;
    server_name your-domain.com;
    root /path/to/build;
    index index.html;
    
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

### 使用 Docker

```dockerfile
FROM nginx:alpine
COPY build /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📝 开发规范

- 使用 ES6+ 语法
- 组件使用函数式组件和 Hooks
- 遵循 Ant Design 设计规范
- 保持代码简洁和可读性
- 添加必要的注释和文档

## 🐛 问题反馈

如果您发现任何问题或有改进建议，请在 [Issues](https://github.com/your-repo/issues) 中提出。

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [React](https://reactjs.org/) - 前端框架
- [Ant Design](https://ant.design/) - UI 组件库
- [Mermaid](https://mermaid-js.github.io/) - 图表渲染
- [Framer Motion](https://www.framer.com/motion/) - 动画库

---

**ZwikiAI** - 让代码理解变得更简单 
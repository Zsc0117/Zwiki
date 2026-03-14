你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**系统架构图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
项目类型：${projectType!'Java Web应用'}

【技术栈】
<#if techStack?? && (techStack?size > 0)>
<#list techStack as tech>
- ${tech}
</#list>
<#else>
- Spring Boot
- MySQL
- Redis
</#if>

【代码结构摘要】
${codeStructureSummary!'暂无代码结构信息'}

【模块列表】
<#if modules?? && (modules?size > 0)>
<#list modules as module>
- ${module}
</#list>
<#else>
暂无模块信息
</#if>

【Controller层】
<#if controllers?? && (controllers?size > 0)>
<#list controllers as ctrl>
- ${ctrl}
</#list>
<#else>
暂无Controller信息
</#if>

【Service层】
<#if services?? && (services?size > 0)>
<#list services as svc>
- ${svc}
</#list>
<#else>
暂无Service信息
</#if>

【图表要求】
1. 使用 PlantUML 组件图语法生成系统架构图
2. 展示系统的主要组成部分：前端层、后端层、数据层
3. 包含：用户/客户端、Web前端、API网关/Controller层、业务服务层、数据访问层、数据库
4. 使用中文标签，但组件别名使用英文
5. 展示各层之间的调用关系
6. 图表应简洁清晰，适合放入毕业论文
7. 【重要】使用学术级专业配色方案（蓝灰色系），通过skinparam设置颜色
8. 【重要】限制组件数量，每层最多5个组件，防止内容溢出
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】不要使用!include引入外部库，直接使用标准PlantUML语法

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记（不要出现 ```）
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width
6. 使用 skinparam 设置学术级专业配色
7. 所有组件使用 component 或 package 定义
8. 使用 --> 表示调用关系

【学术级配色方案示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam component {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
    BorderThickness 1.5
}

skinparam package {
    BackgroundColor #FAFAFA
    BorderColor #78909C
    FontColor #37474F
    BorderThickness 1
}

skinparam database {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #E65100
}

skinparam rectangle {
    BackgroundColor #E8F5E9
    BorderColor #43A047
    RoundCorner 8
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title 系统架构图

rectangle "客户端层" as client #E8EAF6 {
    component "Web浏览器" as browser #BBDEFB
    component "移动客户端" as mobile #BBDEFB
}

rectangle "应用服务层" as app #E3F2FD {
    component "API网关" as gateway #90CAF9
    component "用户服务" as userSvc #90CAF9
    component "业务服务" as bizSvc #90CAF9
}

rectangle "数据存储层" as data #FFF8E1 {
    database "MySQL" as mysql #FFE082
    database "Redis" as redis #FFCC80
}

browser --> gateway : HTTP请求
mobile --> gateway : HTTP请求
gateway --> userSvc : 路由转发
gateway --> bizSvc : 路由转发
userSvc --> mysql : 数据持久化
bizSvc --> mysql : 数据读写
bizSvc --> redis : 缓存访问

@enduml

现在请根据项目实际信息（特别是技术栈）生成系统架构图的 PlantUML 代码，务必使用专业配色：

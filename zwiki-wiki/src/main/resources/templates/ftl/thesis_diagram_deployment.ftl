你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**部署图**的 PlantUML 代码。

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
- Nginx
</#if>

【部署信息】
<#if deploymentInfo??>
${deploymentInfo}
<#else>
标准Web应用部署架构
</#if>

【服务模块】
<#if services?? && (services?size > 0)>
<#list services as svc>
- ${svc}
</#list>
<#else>
暂无服务模块信息
</#if>

【图表要求】
1. 使用 PlantUML 部署图语法
2. 展示系统的物理部署架构
3. 使用 node 定义服务器/容器节点
4. 使用 artifact 或 component 定义部署的软件
5. 展示节点之间的通信关系
6. 包含：客户端、Web服务器、应用服务器、数据库服务器、缓存服务器等
7. 适合放入毕业论文展示
8. 【重要】使用学术级专业配色方案，通过skinparam设置颜色
9. 【重要】添加 scale 750 width 控制图片宽度，防止内容溢出
10. 【重要】节点数量控制在合理范围，避免图表过于复杂
11. 【重要】不要使用!include引入外部库，直接使用标准PlantUML语法

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色部署图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam node {
    BackgroundColor #E8EAF6
    BorderColor #3F51B5
    FontColor #1A237E
    BorderThickness 1.5
}

skinparam database {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #BF360C
    BorderThickness 1.5
}

skinparam component {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title 部署架构图

node "客户端" as client #E1F5FE {
    component "Web浏览器" as browser #BBDEFB
    component "移动APP" as mobile #BBDEFB
}

node "负载均衡层" as lb #E8F5E9 {
    component "Nginx" as nginx #A5D6A7
}

node "应用服务器集群" as appCluster #E3F2FD {
    component "Spring Boot 1" as app1 #90CAF9
    component "Spring Boot 2" as app2 #90CAF9
}

node "数据层" as dataLayer #FFF8E1 {
    database "MySQL" as mysql #FFE082
    database "Redis" as redis #FFCC80
}

client --> lb : HTTPS
lb --> appCluster : 负载分发
appCluster --> mysql : JDBC连接
appCluster --> redis : 缓存读写

@enduml

现在请根据项目实际技术栈生成部署图的 PlantUML 代码，务必使用专业配色：

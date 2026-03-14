你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**组件图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
项目类型：${projectType!'Java Web应用'}

【模块/组件信息】
<#if components?? && (components?size > 0)>
<#list components as comp>
组件: ${comp.name!'Unknown'}
<#if comp.description??>
描述: ${comp.description}
</#if>
<#if comp.dependencies?? && (comp.dependencies?size > 0)>
依赖:
<#list comp.dependencies as dep>
  - ${dep}
</#list>
</#if>
---
</#list>
<#else>
暂无组件信息
</#if>

【包结构】
<#if packages?? && (packages?size > 0)>
<#list packages as pkg>
- ${pkg}
</#list>
<#else>
暂无包信息
</#if>

【技术栈】
<#if techStack?? && (techStack?size > 0)>
<#list techStack as tech>
- ${tech}
</#list>
<#else>
- Spring Boot
- MyBatis/JPA
- MySQL
- Redis
</#if>

【图表要求】
1. 使用 PlantUML 组件图语法
2. 展示系统的主要组件及其接口
3. 使用 component 定义组件
4. 使用 interface 定义接口（可用小圆圈表示）
5. 展示组件之间的依赖关系
6. 可以使用 package 对相关组件进行分组
7. 保持图表清晰，适合论文展示
8. 【重要】使用学术级专业配色方案，通过skinparam设置颜色
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】组件数量控制在合理范围，防止溢出
11. 【重要】不要使用!include引入外部库，直接使用标准PlantUML语法

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色组件图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false
skinparam componentStyle uml2

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
    FontStyle bold
}

skinparam interface {
    BackgroundColor #E8F5E9
    BorderColor #43A047
}

skinparam database {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #E65100
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title 组件图

package "前端层" as frontend #E1F5FE {
    component "Web前端" as web #BBDEFB
    component "移动端" as mobile #BBDEFB
}

package "应用服务层" as appLayer #E8F5E9 {
    component "用户服务" as userSvc #C8E6C9
    component "业务服务" as bizSvc #C8E6C9
    
    interface "REST API" as restApi #A5D6A7
}

package "基础设施层" as infraLayer #FFF8E1 {
    component "数据访问" as dao #FFE082
    component "缓存服务" as cache #FFE082
}

database "MySQL" as mysql #FFCC80
database "Redis" as redis #FFAB91

frontend --> restApi : HTTP请求
restApi -- userSvc
restApi -- bizSvc

userSvc --> dao
bizSvc --> dao
bizSvc --> cache

dao --> mysql : JDBC
cache --> redis : Lettuce

@enduml

现在请根据项目实际组件信息生成组件图的 PlantUML 代码，务必使用专业配色：

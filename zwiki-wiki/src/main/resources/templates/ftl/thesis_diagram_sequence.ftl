你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**时序图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
核心业务流程：${businessFlow!'用户请求处理流程'}

【API接口信息】
<#if apis?? && (apis?size > 0)>
<#list apis as api>
- ${api}
</#list>
<#else>
暂无API信息
</#if>

【调用链信息】
<#if callChains?? && (callChains?size > 0)>
<#list callChains as chain>
${chain}
</#list>
<#else>
Controller -> Service -> Repository -> Database
</#if>

【参与者列表】
<#if participants?? && (participants?size > 0)>
<#list participants as p>
- ${p}
</#list>
<#else>
- 用户/客户端
- Controller控制器
- Service服务层
- Repository数据访问层
- Database数据库
</#if>

【图表要求】
1. 使用 PlantUML 时序图语法
2. 展示一个典型的业务请求处理流程（如用户登录、数据查询等）
3. 参与者包括：用户、Controller、Service、Repository、Database
4. 显示方法调用和返回值
5. 使用中文注释说明关键步骤
6. 保持图表简洁，展示核心交互流程
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度
9. 【重要】参与者数量控制在5-6个，消息数量控制在合理范围

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width
6. 使用 participant 定义参与者，配合 as 别名
7. 消息使用 -> 和 --> 表示同步和异步调用
8. 返回使用 <-- 或 return

【学术级配色时序图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false
skinparam sequenceMessageAlign center
skinparam responseMessageBelowArrow true

skinparam sequence {
    ArrowColor #1976D2
    ArrowThickness 1.5
    LifeLineBorderColor #78909C
    LifeLineBackgroundColor #ECEFF1
    
    ParticipantBorderColor #1565C0
    ParticipantBackgroundColor #E3F2FD
    ParticipantFontColor #0D47A1
    ParticipantFontStyle bold
    
    ActorBorderColor #E65100
    ActorBackgroundColor #FFF3E0
    ActorFontColor #BF360C
    
    BoxBackgroundColor #FAFAFA
    BoxBorderColor #BDBDBD
    
    DividerBackgroundColor #E8EAF6
    DividerBorderColor #7986CB
    
    GroupBackgroundColor #F5F5F5
    GroupBorderColor #9E9E9E
}

skinparam database {
    BackgroundColor #FFF8E1
    BorderColor #FF8F00
    FontColor #E65100
}

title ${projectName!'系统'} 业务时序图

actor "用户" as User #FFF3E0
participant "Controller\n控制层" as Controller #E3F2FD
participant "Service\n业务层" as Service #E8F5E9
participant "Repository\n数据层" as DAO #FFF3E0
database "MySQL" as DB #FFF8E1

== 请求处理流程 ==

User -[#1976D2]> Controller : <color:#1565C0>HTTP请求</color>
activate Controller #BBDEFB

Controller -[#43A047]> Service : <color:#2E7D32>调用业务方法</color>
activate Service #C8E6C9

Service -[#E65100]> DAO : <color:#BF360C>数据查询</color>
activate DAO #FFE0B2

DAO -[#FF8F00]> DB : <color:#E65100>SQL查询</color>
DB --[#FF8F00]> DAO : <color:#E65100>返回数据</color>
deactivate DAO

Service -[#43A047]-> Service : <color:#2E7D32>业务处理</color>

Service --[#43A047]> Controller : <color:#2E7D32>返回结果</color>
deactivate Service

Controller --[#1976D2]> User : <color:#1565C0>JSON响应</color>
deactivate Controller

@enduml

现在请根据项目实际信息生成时序图的 PlantUML 代码，务必使用专业配色和分隔符：

你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**用例图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
系统描述：${systemDescription!'Web应用系统'}

【功能模块】
<#if functionModules?? && (functionModules?size > 0)>
<#list functionModules as module>
模块: ${module.name!'未知模块'}
<#if module.functions?? && (module.functions?size > 0)>
功能:
<#list module.functions as func>
  - ${func}
</#list>
</#if>
---
</#list>
<#else>
暂无功能模块信息
</#if>

【Controller接口分析】
<#if controllers?? && (controllers?size > 0)>
<#list controllers as ctrl>
- ${ctrl}
</#list>
<#else>
暂无Controller信息
</#if>

【用户角色】
<#if actors?? && (actors?size > 0)>
<#list actors as actor>
- ${actor}
</#list>
<#else>
- 普通用户
- 管理员
</#if>

【图表要求】
1. 使用 PlantUML 用例图语法
2. 定义系统边界（使用 rectangle）
3. 包含主要角色（Actor）：普通用户、管理员等
4. 展示核心用例（Use Case）：从Controller提取的主要功能
5. 展示角色与用例的关系
6. 用例之间可以有 include 或 extend 关系
7. 保持图表简洁，最多展示8-10个核心用例
8. 【重要】使用学术级专业配色方案
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】用例数量控制在合理范围，防止溢出

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width
6. 使用 actor 定义角色
7. 使用 usecase 或 (用例名) 定义用例
8. 使用 rectangle 定义系统边界

【学术级配色用例图示例】
@startuml
scale 750 width
left to right direction

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam actor {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #BF360C
    BorderThickness 1.5
}

skinparam usecase {
    BackgroundColor #E8F5E9
    BorderColor #43A047
    FontColor #2E7D32
    BorderThickness 1.5
}

skinparam rectangle {
    BackgroundColor #FAFAFA
    BorderColor #1565C0
    FontColor #0D47A1
    BorderThickness 2
    RoundCorner 10
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 用例图

actor "普通用户" as User #FFF3E0
actor "管理员" as Admin #FFE0B2

rectangle "${projectName!'系统'}" as System #E3F2FD {
    usecase "用户登录" as UC1 #E8F5E9
    usecase "用户注册" as UC2 #E8F5E9
    usecase "查看信息" as UC3 #E8F5E9
    usecase "修改资料" as UC4 #E8F5E9
    usecase "用户管理" as UC5 #BBDEFB
    usecase "系统配置" as UC6 #BBDEFB
    usecase "数据统计" as UC7 #BBDEFB
    
    UC1 .[#43A047].> UC3 : <<include>>
    UC1 .[#43A047].> UC4 : <<include>>
}

User -[#E65100]-> UC1
User -[#E65100]-> UC2
User -[#E65100]-> UC3
User -[#E65100]-> UC4

Admin -[#FF8F00]-> UC1
Admin -[#FF8F00]-> UC5
Admin -[#FF8F00]-> UC6
Admin -[#FF8F00]-> UC7

@enduml

现在请根据项目实际信息生成用例图的 PlantUML 代码，务必使用专业配色：

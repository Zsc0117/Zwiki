你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**数据流图（DFD）**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
数据流描述：${dataFlowDescription!'系统数据流向和处理过程'}

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
1. 使用 PlantUML 组件图语法模拟数据流图
2. 展示外部实体（用户、外部系统）
3. 展示数据处理过程（圆角矩形）
4. 展示数据存储（开口矩形或数据库符号）
5. 展示数据流向（带箭头的线，标注数据名称）
6. 保持图表简洁，处理过程控制在5-8个
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色数据流图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam rectangle {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
    RoundCorner 15
    BorderThickness 1.5
}

skinparam database {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #E65100
}

skinparam actor {
    BackgroundColor #E8F5E9
    BorderColor #43A047
    FontColor #2E7D32
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 数据流图

actor "用户" as User #E8F5E9
actor "管理员" as Admin #FFF3E0

rectangle "1.0\n用户认证" as P1 #E3F2FD
rectangle "2.0\n业务处理" as P2 #E3F2FD
rectangle "3.0\n数据管理" as P3 #E3F2FD
rectangle "4.0\n报表生成" as P4 #E3F2FD

database "用户数据" as D1 #FFF8E1
database "业务数据" as D2 #FFF8E1

User -[#43A047]-> P1 : 登录信息
P1 -[#1565C0]-> D1 : 验证请求
D1 -[#E65100]-> P1 : 用户信息
P1 -[#43A047]-> User : 认证结果

User -[#43A047]-> P2 : 业务请求
P2 -[#1565C0]-> D2 : 数据操作
D2 -[#E65100]-> P2 : 操作结果
P2 -[#43A047]-> User : 处理结果

Admin -[#FF8F00]-> P3 : 管理请求
P3 -[#1565C0]-> D2 : 数据维护

P2 -[#546E7A]-> P4 : 统计数据
P4 -[#FF8F00]-> Admin : 报表

@enduml

现在请根据项目实际信息生成数据流图的 PlantUML 代码，务必使用专业配色：

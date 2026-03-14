你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**状态图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
状态描述：${stateDescription!'系统核心业务对象的状态流转'}
业务流程：${businessFlow!'核心业务处理流程'}

【实体信息】
<#if entities?? && (entities?size > 0)>
<#list entities as entity>
实体: ${entity.name!'Unknown'}
<#if entity.fields?? && (entity.fields?size > 0)>
字段:
<#list entity.fields as field>
  - ${field}
</#list>
</#if>
---
</#list>
<#else>
暂无实体信息
</#if>

【图表要求】
1. 使用 PlantUML 状态图语法
2. 展示核心业务对象（如订单、用户、任务等）的状态转换
3. 包含初始状态、最终状态、中间状态
4. 展示状态之间的转换条件和触发事件
5. 可使用复合状态表示子状态
6. 保持图表简洁，状态数量控制在6-10个
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色状态图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam state {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
    BorderThickness 1.5
    StartColor #43A047
    EndColor #E53935
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 状态转换图

[*] --> 待处理 : 创建

state "待处理" as Pending #E3F2FD {
}

state "处理中" as Processing #FFF3E0 {
}

state "已完成" as Completed #E8F5E9 {
}

state "已取消" as Cancelled #FFEBEE {
}

Pending --> Processing : 开始处理
Processing --> Completed : 处理成功
Processing --> Pending : 退回重做
Pending --> Cancelled : 用户取消
Processing --> Cancelled : 超时取消

Completed --> [*]
Cancelled --> [*]

note right of Processing
  处理中状态可能包含
  多个子处理阶段
end note

@enduml

现在请根据项目实际信息生成状态图的 PlantUML 代码，务必使用专业配色：

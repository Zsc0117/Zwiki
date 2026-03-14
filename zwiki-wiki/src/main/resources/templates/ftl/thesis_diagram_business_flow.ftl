你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**业务流程图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}
业务流程：${businessFlow!'核心业务处理流程'}

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
1. 使用 PlantUML 活动图语法展示业务流程
2. 使用泳道（swimlane/partition）区分不同角色或部门
3. 展示业务的主要步骤和决策点
4. 包含开始、结束、活动、判断、并行等元素
5. 标注关键业务数据和状态变化
6. 保持图表简洁，流程步骤控制在10-15个
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色业务流程图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam swimlane {
    BorderColor #1565C0
    TitleBackgroundColor #E3F2FD
    TitleFontColor #0D47A1
}

skinparam activity {
    BackgroundColor #E8F5E9
    BorderColor #43A047
    FontColor #2E7D32
    BarColor #43A047
    BorderThickness 1.5
}

skinparam diamond {
    BackgroundColor #FFF3E0
    BorderColor #E65100
    FontColor #E65100
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 业务流程图

|#E3F2FD|用户|
start
:发起业务请求;
:填写业务信息;

|#E8F5E9|系统|
:接收请求;
:数据校验;

if (数据有效?) then (是)
    :保存业务数据;
    :生成业务单据;
    
    |#FFF3E0|审核人员|
    :审核业务;
    
    if (审核通过?) then (是)
        |#E8F5E9|系统|
        :执行业务逻辑;
        :更新业务状态;
        :发送通知;
        
        |#E3F2FD|用户|
        :接收结果通知;
        :确认完成;
    else (否)
        |#E3F2FD|用户|
        :收到退回通知;
        :修改并重新提交;
        detach
    endif
else (否)
    |#E3F2FD|用户|
    :显示错误信息;
    :修正数据;
    detach
endif

stop

@enduml

现在请根据项目实际业务信息生成业务流程图的 PlantUML 代码，务必使用专业配色和泳道：

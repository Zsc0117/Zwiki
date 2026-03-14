你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**活动图**的 PlantUML 代码。

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
1. 使用 PlantUML 活动图语法
2. 展示系统核心业务的处理流程
3. 包含开始节点、结束节点、活动节点、判断分支
4. 使用泳道（partition）区分不同的处理层或角色
5. 展示条件判断和并行处理（如有）
6. 保持图表简洁，活动节点控制在10-15个
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色活动图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam activity {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
    BarColor #1565C0
    BorderThickness 1.5
}

skinparam partition {
    BackgroundColor #FAFAFA
    BorderColor #78909C
    FontColor #37474F
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 业务活动图

start

partition "用户层" #E8F5E9 {
    :用户发起请求;
    :参数校验;
}

partition "业务层" #E3F2FD {
    if (权限验证?) then (通过)
        :执行业务逻辑;
        :数据处理;
    else (失败)
        :返回错误信息;
        stop
    endif
}

partition "数据层" #FFF3E0 {
    :数据持久化;
    :更新缓存;
}

:返回处理结果;

stop

@enduml

现在请根据项目实际信息生成活动图的 PlantUML 代码，务必使用专业配色：

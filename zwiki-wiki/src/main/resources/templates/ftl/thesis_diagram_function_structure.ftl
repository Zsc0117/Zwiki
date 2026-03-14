你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目信息，生成一个**功能结构图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}

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

【模块详情】
<#if modules?? && (modules?size > 0)>
<#list modules as module>
<#if module.name??>
- ${module.name}: ${module.description!''}
</#if>
</#list>
<#else>
暂无模块详情
</#if>

【Controller层】
<#if controllers?? && (controllers?size > 0)>
<#list controllers as ctrl>
- ${ctrl}
</#list>
<#else>
暂无Controller信息
</#if>

【图表要求】
1. 使用 PlantUML WBS（工作分解结构）或MindMap语法
2. 以系统名称为根节点
3. 第一层为主要功能模块
4. 第二层为子功能/功能点
5. 可使用第三层展示更细粒度的功能
6. 保持层次清晰，每层节点不超过6个
7. 【重要】使用学术级专业配色方案
8. 【重要】添加 scale 750 width 控制图片宽度

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startwbs 或 @startmindmap
4. 最后一行必须是 @endwbs 或 @endmindmap
5. 必须在开始标签后添加 scale 750 width

【学术级配色功能结构图示例】
@startwbs
scale 750 width

<style>
wbsDiagram {
    BackgroundColor #FFFFFF
    
    node {
        BackgroundColor #E3F2FD
        LineColor #1565C0
        FontColor #0D47A1
        RoundCorner 10
        LineThickness 1.5
        Padding 8
        Margin 3
    }
    
    :depth(0) {
        BackgroundColor #1565C0
        FontColor #FFFFFF
        FontStyle bold
    }
    
    :depth(1) {
        BackgroundColor #E3F2FD
        FontColor #0D47A1
    }
    
    :depth(2) {
        BackgroundColor #E8F5E9
        FontColor #2E7D32
    }
    
    :depth(3) {
        BackgroundColor #FFF3E0
        FontColor #E65100
    }
    
    arrow {
        LineColor #546E7A
        LineThickness 1.5
    }
}
</style>

* ${projectName!'系统'}
** 用户管理
*** 用户注册
*** 用户登录
*** 用户信息管理
*** 权限管理
** 业务功能
*** 数据录入
*** 数据查询
*** 数据分析
*** 报表导出
** 系统管理
*** 系统配置
*** 日志管理
*** 数据备份

@endwbs

现在请根据项目实际功能模块信息生成功能结构图的 PlantUML 代码，务必使用专业配色：

你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**模块结构图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}

【模块信息】
<#if modules?? && (modules?size > 0)>
<#list modules as module>
模块: ${module.name!'Unknown'}
<#if module.description??>
描述: ${module.description}
</#if>
<#if module.subModules?? && (module.subModules?size > 0)>
子模块:
<#list module.subModules as sub>
  - ${sub}
</#list>
</#if>
---
</#list>
<#else>
暂无模块信息
</#if>

【包结构】
<#if packages?? && (packages?size > 0)>
<#list packages as pkg>
- ${pkg}
</#list>
<#else>
暂无包信息
</#if>

【功能概述】
<#if functionSummary??>
${functionSummary}
<#else>
暂无功能概述
</#if>

【图表要求】
1. 使用 PlantUML 包图或组件图语法
2. 展示系统的模块划分和层次结构
3. 使用 package 或 rectangle 定义模块
4. 展示模块之间的依赖关系
5. 可以嵌套展示子模块
6. 模块名使用中文，便于论文展示
7. 保持图表层次清晰
8. 【重要】使用学术级专业配色方案（不同模块不同颜色）
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】模块数量控制在合理范围，防止溢出

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色模块结构图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false

skinparam package {
    BorderThickness 1.5
    FontStyle bold
}

skinparam component {
    BackgroundColor #ECEFF1
    BorderColor #607D8B
    FontColor #37474F
    BorderThickness 1
}

skinparam rectangle {
    RoundCorner 10
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 模块结构图

rectangle "${projectName!'系统'}" as System #FAFAFA {
    
    package "用户模块" as UserModule #E3F2FD {
        component "用户管理" as um1 #BBDEFB
        component "权限管理" as um2 #BBDEFB
        component "登录认证" as um3 #BBDEFB
    }
    
    package "业务模块" as BizModule #E8F5E9 {
        component "订单管理" as bm1 #C8E6C9
        component "商品管理" as bm2 #C8E6C9
        component "支付管理" as bm3 #C8E6C9
    }
    
    package "系统模块" as SysModule #FFF3E0 {
        component "日志管理" as sm1 #FFE0B2
        component "配置管理" as sm2 #FFE0B2
        component "监控管理" as sm3 #FFE0B2
    }
    
    package "基础模块" as BaseModule #FCE4EC {
        component "数据访问" as base1 #F8BBD9
        component "缓存服务" as base2 #F8BBD9
        component "文件服务" as base3 #F8BBD9
    }
}

UserModule -[#1565C0,thickness=2]-> BaseModule : <color:#1565C0>依赖</color>
BizModule -[#43A047,thickness=2]-> BaseModule : <color:#43A047>依赖</color>
BizModule -[#43A047,thickness=2]-> UserModule : <color:#43A047>调用</color>
SysModule -[#E65100,thickness=2]-> BaseModule : <color:#E65100>依赖</color>

@enduml

现在请根据项目实际信息生成模块结构图的 PlantUML 代码，务必使用专业配色：

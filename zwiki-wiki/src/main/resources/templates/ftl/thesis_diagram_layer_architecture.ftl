你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**分层架构图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}

【代码结构分层】
<#if layers??>
<#list layers?keys as layerName>
${layerName}层：
<#list layers[layerName] as className>
  - ${className}
</#list>
</#list>
<#else>
暂无分层信息
</#if>

【包结构】
<#if packages?? && (packages?size > 0)>
<#list packages as pkg>
- ${pkg}
</#list>
<#else>
暂无包信息
</#if>

【图表要求】
1. 使用 PlantUML 包图语法生成分层架构图
2. 清晰展示系统的分层结构：Controller层 -> Service层 -> Repository层 -> Entity层
3. 每层包含该层的主要类（最多显示4-5个核心类，防止溢出）
4. 使用箭头表示层与层之间的依赖关系
5. 中文层名，英文类名
6. 图表简洁，适合论文展示
7. 【重要】使用学术级专业配色方案（每层不同颜色，渐变效果）
8. 【重要】添加 scale 750 width 控制图片宽度
9. 【重要】使用 top to bottom direction 保证分层清晰

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width

【学术级配色分层架构图示例】
@startuml
scale 750 width
top to bottom direction

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false
skinparam classAttributeIconSize 0
hide empty members

skinparam package {
    BorderThickness 1.5
    FontStyle bold
}

skinparam class {
    BorderThickness 1
    FontColor #212121
}

title ${projectName!'系统'} 分层架构图

package "表示层 Controller" as controller #E3F2FD {
    class UserController #BBDEFB
    class OrderController #BBDEFB
    class ProductController #BBDEFB
}

package "业务层 Service" as service #E8F5E9 {
    class UserService #C8E6C9
    class OrderService #C8E6C9
    class ProductService #C8E6C9
}

package "数据访问层 Repository" as repository #FFF3E0 {
    interface IUserDAO #FFE0B2
    interface IOrderDAO #FFE0B2
    interface IProductDAO #FFE0B2
}

package "领域层 Entity" as entity #FCE4EC {
    class UserEntity #F8BBD9
    class OrderEntity #F8BBD9
    class ProductEntity #F8BBD9
}

controller -[#1565C0,thickness=2]-> service : <color:#1565C0>业务调用</color>
service -[#43A047,thickness=2]-> repository : <color:#43A047>数据访问</color>
repository -[#E65100,thickness=2]-> entity : <color:#E65100>实体操作</color>

note right
  **层级职责说明**
  ----
  Controller: 接收请求，参数校验
  Service: 业务逻辑处理
  Repository: 数据持久化
  Entity: 领域模型
end note

@enduml

现在请根据项目实际分层信息生成分层架构图的 PlantUML 代码，务必使用专业配色和图例：

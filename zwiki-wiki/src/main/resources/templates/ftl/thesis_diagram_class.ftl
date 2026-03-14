你是一个专业的软件架构师和 PlantUML 专家。请根据以下项目代码结构信息，生成一个**核心类图**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}

【核心类信息】
<#if classes?? && (classes?size > 0)>
<#list classes as cls>
类名: ${cls.name!'Unknown'}
类型: ${cls.type!'class'}
包名: ${cls.packageName!'default'}
<#if cls.fields?? && (cls.fields?size > 0)>
字段:
<#list cls.fields as field>
  - ${field}
</#list>
</#if>
<#if cls.methods?? && (cls.methods?size > 0)>
方法:
<#list cls.methods as method>
  - ${method}
</#list>
</#if>
<#if cls.dependencies?? && (cls.dependencies?size > 0)>
依赖:
<#list cls.dependencies as dep>
  - ${dep}
</#list>
</#if>
---
</#list>
<#else>
暂无类信息
</#if>

【图表要求】
1. 使用 PlantUML 类图语法
2. 展示核心类的属性和方法（每个类最多展示3-4个核心属性和方法）
3. 使用正确的UML符号：+ public, - private, # protected
4. 展示类之间的关系：继承(--|>)、实现(..|>)、依赖(..>)、关联(--> )
5. 接口使用 interface 关键字
6. 抽象类使用 abstract 关键字
7. 图表应清晰展示系统核心领域模型
8. 【重要】使用学术级专业配色方案（不同类型不同颜色）
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】类数量控制在6-8个，防止图表过大溢出

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width
6. 类名使用英文，注释可用中文

【学术级配色类图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false
skinparam classAttributeIconSize 0
skinparam wrapWidth 200

skinparam class {
    BackgroundColor #E8F5E9
    BorderColor #2E7D32
    FontColor #1B5E20
    HeaderBackgroundColor #C8E6C9
    AttributeFontColor #33691E
    BorderThickness 1.5
    FontSize 11
}

skinparam interface {
    BackgroundColor #E3F2FD
    BorderColor #1565C0
    FontColor #0D47A1
    HeaderBackgroundColor #BBDEFB
    BorderThickness 1.5
}

skinparam stereotype {
    CBackgroundColor #FFF3E0
    ABackgroundColor #FCE4EC
    IBackgroundColor #E3F2FD
    EBackgroundColor #F3E5F5
}

skinparam arrow {
    Color #546E7A
    Thickness 1.5
}

title ${projectName!'系统'} 核心类图

class UserService <<Service>> #E8F5E9 {
    -userRepository: IUserDAO
    -passwordEncoder: PasswordEncoder
    --
    +register(dto: UserDTO): User
    +login(username, password): Token
    +getUserInfo(userId): UserVO
}

interface IUserDAO <<Repository>> #E3F2FD {
    +findByUsername(username): User
    +save(user): User
    +deleteById(id): void
}

class User <<Entity>> #FFF3E0 {
    -id: Long
    -username: String
    -password: String
    --
    +getId(): Long
    +getUsername(): String
}

class UserDTO <<DTO>> #FCE4EC {
    -username: String
    -password: String
    -email: String
}

UserService -[#2E7D32,thickness=2]-> IUserDAO : 依赖注入
IUserDAO .[#1565C0,thickness=2].> User : 数据操作
UserService .[#E65100,thickness=2].> UserDTO : 参数转换

@enduml

现在请根据项目实际类信息生成核心类图的 PlantUML 代码，务必使用专业配色和类型标记：

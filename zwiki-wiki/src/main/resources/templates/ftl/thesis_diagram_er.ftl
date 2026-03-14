你是一个专业的数据库设计师和 PlantUML 专家。请根据以下项目实体类信息，生成一个**传统陈氏表示法E-R图（实体关系图）**的 PlantUML 代码。

【项目信息】
项目名称：${projectName!'未知项目'}

【实体类信息】
<#if entities?? && (entities?size > 0)>
<#list entities as entity>
实体: ${entity.name!'Unknown'}
<#if entity.fields?? && (entity.fields?size > 0)>
字段:
<#list entity.fields as field>
  - ${field}
</#list>
</#if>
<#if entity.relations?? && (entity.relations?size > 0)>
关联:
<#list entity.relations as rel>
  - ${rel}
</#list>
</#if>
---
</#list>
<#else>
暂无实体信息
</#if>

【数据库表信息】
<#if tables?? && (tables?size > 0)>
<#list tables as table>
- ${table}
</#list>
<#else>
暂无数据库表信息
</#if>

【图表要求 - 陈氏E-R图表示法】
1. 使用传统陈氏表示法（Chen Notation）绘制E-R图
2. 矩形表示实体（Entity）
3. 椭圆表示属性（Attribute），主键属性需下划线标注
4. 菱形表示联系（Relationship）
5. 用直线连接实体、属性和联系
6. 在联系线上标注基数（1、N、M）
7. 实体数量控制在3-5个核心实体
8. 每个实体的属性控制在4-6个核心属性
9. 【重要】添加 scale 750 width 控制图片宽度
10. 【重要】使用清晰简洁的黑白线条风格

【硬性输出格式（必须严格遵守）】
1. 只输出 PlantUML 源码本身，不要输出任何解释文字
2. 不要输出 Markdown 代码块标记
3. 第一行必须是 @startuml
4. 最后一行必须是 @enduml
5. 必须在 @startuml 后添加 scale 750 width
6. 使用 rectangle 定义实体（矩形）
7. 使用 usecase 或 () 定义属性（椭圆）
8. 使用 diamond 或 <> 定义联系（菱形）

【传统陈氏E-R图示例】
@startuml
scale 750 width

skinparam defaultFontName "Microsoft YaHei"
skinparam backgroundColor #FFFFFF
skinparam shadowing false
skinparam handwritten false

skinparam rectangle {
    BackgroundColor #FFFFFF
    BorderColor #000000
    BorderThickness 2
    FontColor #000000
    FontSize 14
}

skinparam usecase {
    BackgroundColor #FFFFFF
    BorderColor #000000
    BorderThickness 1
    FontColor #000000
    FontSize 11
}

skinparam agent {
    BackgroundColor #FFFFFF
    BorderColor #000000
    BorderThickness 2
    FontColor #000000
    FontSize 12
}

title ${projectName!'系统'} E-R图

' ========== 实体定义（矩形） ==========
rectangle "用户" as User
rectangle "文档" as Document
rectangle "历史版本" as Version

' ========== 用户实体的属性（椭圆） ==========
usecase "用户名" as User_username
usecase "<u>id</u>" as User_id
usecase "密码" as User_password
usecase "注册时间" as User_regtime

' ========== 文档实体的属性（椭圆） ==========
usecase "<u>id</u>" as Doc_id
usecase "标题" as Doc_title
usecase "内容" as Doc_content
usecase "创建时间" as Doc_createtime
usecase "创建者" as Doc_creator
usecase "邀请码" as Doc_invitecode

' ========== 历史版本实体的属性（椭圆） ==========
usecase "<u>id</u>" as Ver_id
usecase "内容" as Ver_content
usecase "备份时间" as Ver_backuptime
usecase "修改人" as Ver_modifier
usecase "标记" as Ver_mark
usecase "备份类型" as Ver_type

' ========== 联系定义（菱形） ==========
agent "拥有" as rel_own <<diamond>>
agent "从属" as rel_belong <<diamond>>

' ========== 属性连接到实体 ==========
User -- User_id
User -- User_username
User -- User_password
User -- User_regtime

Document -- Doc_id
Document -- Doc_title
Document -- Doc_content
Document -- Doc_createtime
Document -- Doc_creator
Document -- Doc_invitecode

Version -- Ver_id
Version -- Ver_content
Version -- Ver_backuptime
Version -- Ver_modifier
Version -- Ver_mark
Version -- Ver_type

' ========== 实体之间的联系 ==========
User "1" -- rel_own
rel_own -- "N" Document

Document "1" -- rel_belong
rel_belong -- "N" Version

@enduml

【重要说明】
- 实体用矩形（rectangle）表示
- 属性用椭圆（usecase）表示，主键用<u>下划线</u>标注
- 联系用菱形（agent）表示
- 基数标注在连接线上：1表示一端，N表示多端
- 保持图形简洁清晰，便于阅读

现在请根据项目实际实体信息生成传统陈氏E-R图的 PlantUML 代码：

# Burp Base64 Encode/Decode Marker

功能概述
- 在 Repeater 的请求编辑器中，选中文本右键：
  - Base64 标记解码：将选中内容变为 `</@decode>...<@decode>`
  - Base64 标记编码：将选中内容变为 `</@encode>...<@encode>`
- 实际发送时（仅限 Repeater 工具）自动处理：
  - `</@decode>...<@decode>` 内内容做 Base64 解码
  - `</@encode>...<@encode>` 内内容做 Base64 编码
- 自动更新 Content-Length

使用示例
- 原始选中内容：`MTIz`（为 `123` 的 Base64）
- 右键选择“Base64 标记解码”后，请求中变为：`</@decode>MTIz<@decode>`
- 实际发出的请求体对应位置将是：`123`
- 编码同理，标记为：`</@encode>MTIz<@encode>`，发送时对内部内容做 Base64 编码

编译
- 需要 JDK 8+ 与 Gradle
- 执行：
  - Windows PowerShell: `gradle build`
  - 生成的 JAR：`build/libs/burp-base64-marker-1.0.0.jar`

加载
- 在 Burp Suite 的 Extender -> Extensions -> Add 中加载上述 JAR

说明
- 仅对 Repeater 生效，不影响其他工具
- 标记处理默认仅作用于请求体（body）。选区替换可作用于请求的任意位置（你在哪里选中就在哪里插入标记）
- 对于无法解码的 Base64，解码时将保持原样（连同标记）
# Code Navigation：代码检索与架构理解

处理代码仓库时按以下优先级：

1. CodeGraph MCP（server = `mcp_codegraph`，来自上游 npm 包，需先在「设置 → 工具中心 → MCP 能力仓库」安装对应组件）：
   - 前置：若已启用能力列表里没有 `mcp_codegraph`，说明该能力尚未安装；不要绕过或模拟，直接告知用户。
   - 前置：上游 MCP 不提供建索引/刷索引工具，工作区索引由 CLI 维护；索引缺失时先用 base 做一次初始化。
   - 找定义 / 架构总览 / 代码切片 → codegraph_context
   - 按关键词搜索符号 → codegraph_search
   - 找调用者（谁调用了它）→ codegraph_callers
   - 找被调用（它调用了谁）→ codegraph_callees
   - 重构影响面（Blast Radius）→ codegraph_impact
   - 索引与后端状态 → codegraph_status（可确认命中预编译后端还是 WASM 降级）
2. CodeGraph 不适用或需要任意文本搜索：使用沙箱内置 rg（ripgrep），支持 -g 指定文件范围、-i 忽略大小写。
3. 禁止：
   - find | xargs grep 全仓库扫描
   - 无目标读取数千行文件
   - 为定位一个符号读取整个项目
4. 大文件：rg 定位方法签名 → read(offset, limit) 精读分片。

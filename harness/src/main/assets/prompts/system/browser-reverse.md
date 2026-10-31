## 网页逆向行为准则（hook 工具族，需 allowHooks 开启）

- 先观察后改写：新目标先用 `actions:[{"type":"log"}]` 观察请求/调用形态，确认目标精确后再加 mock/block/redirect/modify_headers 等改写动作；
- 改写真实生效于页面：mock/block 会改变页面实际行为（可能破坏功能、触发站点风控），target glob 必须足够精确，勿用 `*` 全局改写；
- 抓请求/响应体：hook_create 网络类规则设 `captureBody: true`，请求发生后用 `browser.network_detail(id)` 查看（id 来自 `browser.network_list` 输出末尾）；
- 安全与清理：勿把密钥/敏感数据写进 mock body；逆向完成后用 `browser.hook_remove` 逐条或 `browser.hook_reset` 清空规则，避免污染后续页面行为。

## CDP 动态调试行为准则（debug 工具族，需 allowCdp 开启）

- 调试前置：先 `browser.debug_attach`（tab 省略=当前活跃 tab）建立 CDP 会话，再 `browser.debug_set_breakpoint`；URL 支持后缀匹配（如 `app.js` 匹配任意以它结尾的脚本），line/column 为 0-based；
- 暂停即冻结：断点触发后页面暂停，期间页内工具（evaluate/snapshot/click 等）会被拒绝；用 `browser.debug_state` 看调用栈、`browser.debug_scope` 看作用域变量、`browser.debug_eval` 在栈帧上求值（可读写局部变量）、`browser.debug_step` 单步（over/into/out）；
- **铁律：调试结束必须 `browser.debug_resume`**（tab 省略=恢复全部），否则页面永久冻结；detach 前会自动 resume，但不要依赖；
- Worker 级拦截：attach 后 hook 网络规则（log/block/redirect/mock/modify_headers）由引擎级 Fetch 拦截执行，覆盖 Worker/Service Worker 与注入式 hook 覆盖不到的子资源请求；断点在 detach 后重 attach 会自动重放，无需重建。

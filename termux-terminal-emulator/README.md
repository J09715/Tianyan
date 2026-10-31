# termux-terminal-emulator

`termux-app` v0.118.0 的 `terminal-emulator` 模块 fork（GPL-3.0，来源 <https://github.com/termux/termux-app>）。

## 为什么 fork

上游 `com.termux.terminal.TerminalSession` 是 `final`，并把 PTY 硬绑到 `libtermux.so` 的 JNI
（`createSubprocess` 返回裸 fd + `FileInputStream`/`FileOutputStream`），远程 SSH PTY 无法复用同一渲染层。
本 fork 仅新增一个缝隙：

- `TerminalSessionBackend`（新接口）：`start/read/write/setSize/waitForExit/kill/close`。
- `JniTerminalSessionBackend`（默认实现）：等价原 JNI 行为。
- `TerminalSession` 新增构造器 `TerminalSession(String, Integer, TerminalSessionClient, TerminalSessionBackend)`，
  其余 13 个类与 `TerminalSession` 的公共 API 逐字保留。

远程实现（`LinuxSessionTerminalBackend`）位于 `runtime` 模块。2026-10-13 的修改还补充了未附着渲染器时的关闭处理，以及远程会话避免读取手机 `/proc` 的目录查询；原有公共签名保持兼容。GPL-3.0 完整文本见本目录 `COPYING`。

## 二进制兼容

`feature/terminal` 依赖 `terminal-view:0.118.0` AAR，其 `TerminalRenderer` 跨包访问
`com.termux.terminal` 的字段/方法。因此本模块的类名、包名、字段/方法签名与可见性必须与上游一致，
**只允许新增**。升级 `terminal-view` 时必须同步升级本 fork 到同一 tag。

## libtermux.so

预编译 `.so` 从 `repo/com/termux/terminal-emulator/0.118.0/terminal-emulator-0.118.0.aar`
提取（`jni/arm64-v8a/libtermux.so`），SHA-256 记录在 `termux-native.properties`，构建期校验哈希与导出符号。

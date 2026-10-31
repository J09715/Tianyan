# Termux terminal libraries (GPL-3.0)

- `terminal-view-v0.118.0.aar` — Termux `TerminalView` 渲染层，仍作为仓库本地 Maven 依赖
  （`repo/com/termux/terminal-view/0.118.0/`），供 `feature:terminal` 直接使用。
- `terminal-emulator-v0.118.0.aar` — VT100 emulator + PTY JNI。**已不再作为 Gradle 依赖**：
  其 Java 源码已 fork 至根级模块 `termux-terminal-emulator/`（新增 `TerminalSessionBackend` 缝隙），
  `libtermux.so` 从本目录的 AAR 提取到该模块 `src/main/jniLibs/arm64-v8a/`。
  本 AAR 仅作为 `.so` 溯源保留于 `repo/com/termux/terminal-emulator/0.118.0/`。
- Source: https://github.com/termux/termux-app (terminal-emulator / terminal-view)

Packaged from the same AAR set used by Android-PRoot-Engine for interactive PRoot shells.
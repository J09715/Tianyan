# CC-Switch 智能体中枢 — 离线本地插件包

本目录是 `cc-switch` 本地插件的源文件布局，用于打包为可直接导入天衍的本地 `.txplugin`。

## 用途

在 ARM64（`arm64-v8a`）Android 沙箱运行时内，集中纳管与反向代理 Claude Code、OpenClaw、Hermes Agent 与 Codex，提供统一的本地多模型提供商切源、流式协议转换反代与 Token 记账能力。

- 官方项目：[farion1231/cc-switch](https://github.com/farion1231/cc-switch) & [Laliet/cc-switch-web](https://github.com/Laliet/cc-switch-web)
- 对应版本：`v0.21.0`
- 架构：Linux `aarch64-unknown-linux-musl` / `gnu`
- 运行端口：`19870`

## 打包内容

```text
manifest.json
payload/
  checksums/SHA256SUMS
  scripts/install.sh
  scripts/uninstall.sh
  scripts/verify.sh
  bin/cc-switch-daemon
  lib/cc-switch-server
```

## 来源 URL 与哈希

- 服务端二进制：`https://github.com/Laliet/cc-switch-web/releases/download/v0.21.0/cc-switch-server-linux-aarch64`
- 二进制 SHA256：`0e16293cdab1f6a8416a06242deeda41ddd970cbf36e753f05de8ad89d6cd010`

## 打包成 `.txplugin`

在项目根目录下运行：

```bash
python tools/package-cc-switch-plugin.py
```

产物将输出至：`dist/plugins/tianyan-plugin-cc-switch-v1.0.1-arm64.txplugin`。
在天衍内通过“插件中心 - 导入本地插件包”选择此文件即可一键导入安装。

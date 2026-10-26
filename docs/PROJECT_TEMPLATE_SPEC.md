# 天衍可移植项目模板规范（v1）

天衍模板是一个可导入、导出和分享的 ZIP 包。用户可在“工坊 → 更多 → 模板管理”中导入 ZIP，也可导出任意内置模板作为制作样例。

## 1. 目录结构

ZIP 根目录可以直接包含 `template.json`，也可以只包含一个模板目录：

```text
my-template/
├── template.json
├── preview.png                   # 可选，固定 270×270
├── template-hooks/             # 可选
│   ├── before-create.sh
│   └── after-create.sh
└── ...工程文件或 *.template
```

模板包最多 5000 个条目；单文件不超过 16 MiB；总解压体积不超过 128 MiB。ZIP 路径穿越会被拒绝。

## 2. template.json

完整结构由 [project-template.schema.json](project-template.schema.json) 描述。最小示例：

```json
{
  "schemaVersion": 1,
  "id": "example.android-basic",
  "name": "Basic Android",
  "version": "1.0.0",
  "projectType": "ANDROID",
  "category": { "id": "starter", "name": "Starter", "sortOrder": 0 },
  "previewImage": "preview.png",
  "variables": []
}
```

- `id` 只能包含小写字母、数字以及 `.`、`_`、`-` 分隔符。
- `builtin.` 是系统内置模板保留前缀，用户模板不能使用。
- `projectType` 支持 `ANDROID`、`FLUTTER`、`GENERAL`、`TIANYAN_PLUGIN`（天衍本地插件开发）。
- `category` 是模板选择页的第二级分组，`sortOrder` 越小越靠前。
- JSON 采用严格解析；未知字段会被拒绝，避免拼写错误被静默忽略。

## 3. 项目名称与路径

项目名称和创建路径不属于动态字段，由工坊对所有模板统一提供：

- 项目名称必填。
- 路径可留空；内部空间默认创建到 `/workspace/<项目名称>`。
- 模板可使用系统派生变量 `projectName`、`appName` 和 `projectPath`；`appName` 若由动态字段提供则优先使用用户输入。
- 模板可以将 `projectName` 声明为 `prompt: false` 的固定变量，并通过 `validationRegex` 约束项目名称；工坊会在统一的项目名称输入框显示 `description` 提示并阻止无效提交。例如 Flutter 模板可要求名称匹配 `^[a-z][a-z0-9_]*$`。
- 工坊不会静默改写用户输入的项目名称；名称不符合模板规则时必须由用户修改。
- `packageName`、`packagePath` 只有在模板清单中声明时才生成和校验；非 Android 模板不依赖 Java 包名。

## 4. 动态变量

`variables` 支持 `TEXT`、`MULTILINE`、`NUMBER`、`BOOLEAN`、`SELECT`、`SECRET`，还可以声明 `label`、`description`、`placeholder`、`required`、`defaultValue`、`validationRegex` 和 `options`。

- `prompt: true`：创建工程时渲染为动态表单。
- `prompt: false`：不显示；隐藏必填变量必须提供 `defaultValue`，系统派生变量除外。
- 变量名必须匹配 `^[A-Za-z][A-Za-z0-9_]*$`，且不能重复。
- `SELECT` 必须提供至少一个不重复的选项；可选 `SELECT` 允许留空。

## 5. 文件和路径替换

文本内容和相对路径都可使用 `{{variableName}}`，例如 `src/{{moduleName}}/config.json.template`。生成时会替换变量并去掉末尾的 `.template`。

任意需要替换的文本文件都建议添加 `.template` 后缀；常见源码和配置扩展名也会自动按文本处理。Android/Kotlin 包目录使用 `TIANYAN_PACKAGE_PATH`，创建时会替换为包名对应的目录层级。不要使用以下划线开头的占位目录，否则可能被 Android AAPT 忽略。

生成结束前若仍存在 `{{unknownVariable}}`，创建会失败。所有输出路径都会进行规范化和越界检查。

## 6. 预览图

模板通过单一的 `previewImage` 字段声明预览图，不再区分手机、平板或横竖屏。支持 PNG、JPEG、WebP、GIF，必须使用包内相对路径。

硬限制：预览图比例统一为 **1:1**，像素尺寸必须为 **270×270 px**，单图不超过 4 MiB。建议将 Logo、标题和关键内容放在中央安全区，避免紧贴边缘。

## 7. 随身构造脚本

```json
{
  "hooks": {
    "beforeCreate": "template-hooks/before-create.sh",
    "afterCreate": "template-hooks/after-create.sh"
  }
}
```

- 脚本必须位于 `template-hooks/` 内，单个脚本不超过 1 MiB。
- 导入、查看和导出模板不会执行脚本；创建工程时必须由用户明确授权。
- 脚本在 Linux 沙箱的新工程目录中运行，单阶段最长 60 秒。
- `beforeCreate` 在复制模板文件前执行，`afterCreate` 在物化完成后执行。
- 工程路径通过 `TIANYAN_PROJECT_DIR` 提供。
- 变量通过 `TIANYAN_VAR_<大写变量名>` 提供，例如 `packageName` 对应 `TIANYAN_VAR_PACKAGENAME`。
- 任一脚本非零退出会终止创建并显示错误输出。

## 8. 生成结果校验

模板可以自行声明生成后必须满足的条件，不依赖模板 ID 或应用内硬编码：

```json
{
  "validation": {
    "requiredFiles": ["app/src/main/java/TIANYAN_PACKAGE_PATH/MainHook.kt"],
    "forbiddenFiles": ["app/src/main/java/TIANYAN_PACKAGE_PATH/LegacyHook.kt"],
    "contentRules": [
      {
        "path": "app/src/main/assets/xposed_init",
        "equals": "{{packageName}}.MainHook"
      }
    ]
  }
}
```

- `requiredFiles`：必须存在的文件。
- `forbiddenFiles`：不得生成的文件或目录。
- `contentRules`：支持 `equals`、`contains`、`excludes` 文本规则。
- 路径和期望内容都支持模板变量及 `TIANYAN_PACKAGE_PATH`。
- 校验在 `afterCreate` 脚本完成后执行，因此脚本可以参与最终工程构造。

## 9. 导入、导出与分享

导入时会校验清单、变量、分类、预览图、脚本路径及 ZIP 安全限制。用户模板安装在应用私有模板目录，不会覆盖内置模板。

模板管理页支持导入用户 ZIP、导出用户或内置模板，以及删除用户模板；内置模板不可删除。以内置模板为样例制作新模板时，必须修改 `id` 并移除保留的 `builtin.` 前缀。

应用内置模板会从 `project-template/src/main/assets/templates/<项目类型>/<模板目录>/template.json` 自动发现；新增标准目录不需要修改 Kotlin 注册表。

## 10. 天衍本地插件开发

内置模板 `builtin.tianyan-local-plugin` 位于 `templates/tianyan-plugin/process/`，在创建项目首步作为独立类型展示。用户填写工具 ID、点分插件身份、插件名称与初始版本，不需要 Android 包名或 Gradle。创建和归档导入均持久化 `TIANYAN_PLUGIN` 项目类型。

初始文件包括 v2 `manifest.json`、`payload/bin/<toolId>` Shell 示例、`tools/package_plugin.py`、README 与 AGENTS 开发约定。示例支持 `--version`，打包脚本仅依赖 Python 3 标准库，自动计算所有 payload 文件的 SHA-256，将清单和 payload 打成 `dist/<toolId>-<version>.txplugin`，再由插件中心确认导入。Python 需要在运行打包命令前准备；创建工程不会自动安装依赖或执行脚本。

项目卡片提供“一键导入调试”：宿主直接打包清单与 payload，无需 Python 或执行项目脚本，复用插件中心清单确认、完整性校验和安装链路。调试快照保存在工程 `dist/<toolId>-debug-<UUID>.txplugin`，清单版本采用 `<源版本去除构建元数据>+tianyan.debug.<SHA-256前16位>.<随机快照ID>`；指纹覆盖清单与 payload 哈希，源文件不改写。每次点击产生独立版本，回退代码或安装失败后也可重新导入。快照文件保留供手动清理；安装后从插件详情启动、查看日志。调试版本沿用同一插件身份，替换当前系统的已安装版本，不是并行安装，也不提供热重载。

这是 [v2 本地 PROCESS 插件](PLUGIN_PACKAGE_V2.md) 的开发骨架，不提供外部 Compose/Provider 回调加载。不走 Android APK 构建和安装流程。同版本插件包不可覆盖导入，正式发布仍递增源版本并使用 Python 脚本打包。

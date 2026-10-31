# 本地数据备份与恢复

## 覆盖范围

| 类别 | 包含 | 排除 |
|---|---|---|
| 数据库记录 | 模型档案、会话树及分支、技能、子智能体、记忆、计划、工作区清单 | 运行中的 harness_operations（会话专用操作状态）|
| 附件文件 | 聊天附件、大载荷 blob（Harness 大消息）、技能资源目录 | RootFS、Linux 文件系统、缓存 |
| 用户配置 | 界面、Agent 行为、终端等可移植偏好 | API Key、自定义请求头、设备配对、存储挂载路径 |

## 恢复安全保证

1. **恢复记录 (backup_restore_receipt)**：写在同一个数据库事务内，重启后可区分提交成功与事务回滚。
2. **原子日志 (AtomicFile)**：进程中断后重启，会根据日志决定继续还是回滚偏好修改和已提取的文件。
3. **只追加**：已有会话不覆盖，只写入本机没有的记录；现有模型档案保留本机 Key。
4. **路径校验**：所有归档路径通过 `BackupArchive.safePath` 检查，禁止目录穿越和绝对路径。
5. **哈希校验**：每个资源文件在写入前对比 SHA-256 摘要。

## 文件结构

```
backup.zip
├── manifest.json          # BackupLocations、格式版本、记录表快照、资源清单
├── preferences.json       # 可移植偏好（无凭据）
└── assets/
    ├── attachments/       # 聊天附件
    ├── harness_blobs/     # 大消息 payload 文件
    └── skills/<id>/       # 技能资源目录
```

## 限制

- 备份包上限 512 MiB，单文件 50 MiB，最多 2000 个资源文件。
- 备份与恢复期间不能有进行中的 Agent 操作（通过快照一致性检查强制）。
- 工作区实际文件（Linux 沙箱内容）不在备份范围内，需另行管理。

## 相关文件

| 文件 | 说明 |
|---|---|
| `tools/backup/LocalBackupService.kt` | 备份/恢复协调器、原子日志与启动恢复 |
| `tools/backup/BackupArchive.kt` | ZIP 归档读写、路径校验、SHA-256 摘要 |
| `tools/backup/BackupResources.kt` | 资源文件收集与路径重写 |
| `core/database/BackupRecordPolicy.kt` | 可备份表清单、运行时字段归零、记录差集 |
| `core/database/BackupRestoreReceipt.kt` | 恢复收据实体和 Migration 53→54 |
| `core/database/DatabaseBackupRepository.kt` | Room 事务级快照、校验和合并 |
| `core/datastore/BackupPreferences.kt` | 可移植偏好的快照、校验和原子回滚 |
| `core/model/LocalBackup.kt` | `LocalBackupManifest`、`BackupRecords`、`BackupPreview` 数据类 |
| `feature/settings/LocalBackupDialog.kt` | 导出 / 导入 UI（含预览、复选框）|
| `feature/settings/LocalBackupViewModel.kt` | 备份 UI ViewModel |
| `app/BackupStartupRecovery.kt` | Application 启动时检查并完成挂起恢复 |

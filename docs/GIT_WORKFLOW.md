# GitFlow 分支管理

本仓库采用 GitFlow，提交信息继续遵循 Conventional Commits + 中文描述。多会话协作、显式路径暂存和禁止覆盖他方改动等规则仍以 [AGENTS.md](../AGENTS.md) 为准。

## 分支与合并方向

| 分支 | 起点 | 用途 | 合并目标 |
| --- | --- | --- | --- |
| `main` | 已发布历史 | 稳定版本与发布标签 | 不直接开发功能 |
| `develop` | `main` | 集成功能，准备下一版本 | 经 `release/*` 发布 |
| `feature/<名称>` | `develop` | 新功能及普通修复 | `develop` |
| `release/<版本>` | `develop` | 发布收尾、版本号与必要修复 | `main` 和 `develop` |
| `hotfix/<名称>` | `main` | 已发布版本的紧急修复 | `main` 和 `develop`，有活动发布分支时也同步修复 |

功能分支使用小写英文和连字符，例如 `feature/group-team-governance`。发布分支示例为 `release/1.5.0`，正式标签使用 `v1.5.0`。仅在开始发布或紧急修复时创建对应分支。

## 日常开发

先确认工作区与暂存区，再从最新 `develop` 创建功能分支。远端 `develop` 已建立后可使用：

```powershell
git switch develop
git pull --ff-only origin develop
git switch -c feature/功能名称
```

按本次负责的路径暂存，核对 `git status` 和 `git diff --staged` 后提交。推送后创建以 `develop` 为目标的 PR；通过相应离线测试、架构检查和构建后合并。合并时保留功能分支边界，采用 merge commit（`--no-ff`），不把功能分支直接合并进 `main`。

创建提交、推送、合并及发布仍须遵守当前用户的授权范围；流程文档不构成自动执行这些操作的授权。

## 发布与紧急修复

从 `develop` 创建 `release/<版本>`，只做发布准备和必要修复。验收后通过 PR 合并进 `main`，在该发布提交上创建带注释的 `v<版本>` 标签，并把发布修复合并回 `develop`。

紧急修复从 `main` 创建 `hotfix/<名称>`，完成后合并回 `main`，创建修订版标签，并同步回 `develop` 及活动的发布分支，防止下一版本丢失修复。不要用移动已有发布标签或强推代替新版本发布。

现有 CI 的架构检查与 Release smoke 面向所有符合条件的 PR，不限制目标为 `main`；Pages 仍由 `main` 的网站变更触发，正式 Release 仍由 `v*.*.*` 标签触发。建立 GitFlow 不会自动修改远端默认分支、分支保护或发布任何版本。

## 本地配置与首次建立

本次已在仓库级 `.git/config` 设置发布分支 `main`、集成分支 `develop`，以及 `feature/`、`release/`、`hotfix/`、`support/` 前缀和 `v` 标签前缀。它们是本地配置，不随克隆传播，也不要求安装 `git-flow` 命令；普通 Git 命令即可遵循上述流程。

新克隆可手动配置相同的 `gitflow.branch.*` 和 `gitflow.prefix.*` 项；在远端 `develop` 建立后，先检出其跟踪分支，再开展功能开发。远端首次创建 `develop` 和功能分支，需要维护者另行授权推送；本次只建立本地分支。

本次迁移保留原 `main` 与 `origin/main` 的一致基线，`develop` 从该基线建立，团队模板与审议功能提交保存在 `feature/group-team-governance`。完成审核后先合并到 `develop`，再随下一次发布进入 `main`。

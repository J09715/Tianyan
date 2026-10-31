# 🧱 天衍 (Tianyan) — 沙箱执行后端选型 (chroot / namespace vs PRoot)

> **范围**：评估是否用 `chroot`（或 `unshare` + `pivot_root`）替换/补充现有 PRoot 沙箱执行后端。
> **状态**：**已决策 — 不实施**。PRoot 保持为唯一沙箱后端；高性能后端列为长期可选项，默认不排期。
> **决策日期**：2026-09-16
> **影响面**：`runtime/proot`、`runtime/shell`、`runtime/privilege`、`runtime/pty`、`runtime/doctor`、`StorageManager`

---

## 1. 🎯 背景与问题

PRoot 通过 `ptrace` 逐个拦截并改写 syscall，在 syscall 密集负载上开销约 2–10 倍。天衍恰好把最重的此类负载放在沙箱内：

- Gradle / AGP 的 Android 构建（见 [`ANDROID_OFFLINE_PLUGIN.md`](ANDROID_OFFLINE_PLUGIN.md)）
- `dpkg` / `apt` 解包（见 [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) §6 硬链接防御）
- `npm` / `pnpm` 依赖安装

`chroot` 是内核原生的根目录切换，没有 ptrace 开销，因此提出：是否在已 Root 的设备上改用 chroot。

---

## 2. ⚖️ 三档执行模式与 chroot 可行性

chroot 的门槛不是"有特权"，而是具体需要 `CAP_SYS_CHROOT`；要挂载 `/proc`、`/dev` 和 bind mount 还需 `CAP_SYS_ADMIN`。

| `ExecutionMode` | 运行身份 | 能 chroot | 说明 |
| :--- | :--- | :--- | :--- |
| `PROOT` | app uid (10xxx) | ❌ | 无任何相关 capability |
| `SHIZUKU` | shell uid 2000 | ❌ | **ADB 级别不含 `CAP_SYS_CHROOT` / `CAP_SYS_ADMIN`** |
| `ROOT` | uid 0 | ✅ | 仅此一档可行 |

**关键结论**：Shizuku 是天衍特权能力的主力场景（`PrivilegeManager` 的 `applyPrivilegeOptimizations`、幽灵进程解除、`appops` 自授权都在这一档），但它**完全吃不到** chroot 的收益。受益面仅限已 Root 设备。

### 2.1 现状：ROOT 模式不参与沙箱执行

`ExecutionMode.ROOT` 目前只被以下文件引用：

```
runtime/privilege/PrivilegeManager.kt        ── 宿主侧 su 执行、探测、降级
harness/ToolExecutor.kt                      ── 工具侧模式判断
harness/prompt/PrivilegeSectionRenderer.kt   ── 提示词渲染
feature/chat/ChatViewModel.kt                ── UI 状态
```

`runtime/proot` 与 `runtime/shell` **完全没有引用它**。即用户已授予 Root，沙箱内一切仍走 PRoot；root 只用于宿主侧执行 `settings put` / `appops set` 这类 Android 命令。

因此"上 chroot"不是加一个开关，而是**新建一整条并行执行链路**。

---

## 3. ✅ 决策

1. **PRoot 保持唯一沙箱后端**，不引入 chroot 作为替换或默认路径。无 Root 可用是天衍的核心产品定位，任何以 root 为前提的路径都不能进入主链路。
2. **裸 `chroot` 永久否决**。在 Android 上它必须搭配全局 mount namespace 的 bind mount（见 §4.3），残留风险不可接受。
3. 若未来确有性能刚需，**唯一可接受形态**是 ROOT 模式下的 `unshare --mount --pid --fork` + `pivot_root` 可选后端，默认关闭、失败自动降级（见 §6）。
4. 优先榨取 PRoot 侧余量（见 §7），而不是新开后端。

---

## 4. 🔒 Android 平台约束（按难度递增）

### 4.1 SELinux — 基本不是障碍

Magisk / KernelSU / APatch 的 su 不只是换 uid，它们会往 sepolicy 注入补丁，把 su 出来的进程放进一个**声明为 permissive 的 domain**（Magisk 为 `u:r:magisk:s0`）。permissive domain 下策略违规只记 audit 日志，不实际拒绝。

且 `/data/data/<pkg>/` 下的文件是 `app_data_file` 类型，从该 permissive domain 执行它们**没有对应的 domain transition 规则**，整棵沙箱进程树留在 permissive 内。Linux Deploy / AndroNix root 模式长期可用即基于此。

**前提**：必须真的经过 su 的 daemon。若在 App 进程内 fork 后直接调 `chroot`/`mount`，进程仍在 `untrusted_app` domain 且 enforcing，会被拒绝。

### 4.2 seccomp — 继承且不可撤销

Android 给 app 进程安装了 zygote seccomp filter，`fork` / `execve` 均继承，内核不允许解除。mount 类特权 syscall 不在 app 白名单内，**即使有 root，从 App 进程直接派生的子进程也调不动**。

绕开方式只有一条：Magisk 的 su 是 client-daemon 架构 —— `su` 客户端连接 magiskd，由 magiskd fork 出特权进程，再通过 `SCM_RIGHTS` 接收客户端的 stdin/stdout/stderr。因此真正干活的进程是 **magiskd 的子进程，不带 App 的 seccomp filter**。

**推论**：
- 现有 `HostProcessRunner`（`ProcessBuilder("su", "-c", command)`）恰好是唯一正确的载体。
- `runtime/pty/NativePtySession` 当前在 App 进程内 `forkpty` 后直接 exec PRoot，这条路**不能直接改成 chroot**；必须让 forkpty 的子进程 exec `su`，把 PTY slave 当 stdio 传进去。终端能保住，但 PTY 链路需重接。

### 4.3 mount namespace 归属 — 最隐蔽的坑

Android 每个 app 进程有自己的 mount namespace（zygote fork 时 `unshare(CLONE_NEWNS)`，用于 per-app 存储沙箱）。magiskd 在另一个 namespace 内。

于是 `su -c "mount --bind ..."` 挂出来的东西 **App 自己的进程看不见**，反之亦然。Magisk 提供 `su -mm`（`--mount-master`）进入全局 namespace，但那意味着 bind mount 对**整机所有进程**可见：

> 残留的 `/data/data/top.tianyan.app/.../rootfs` bind mount 会挂在全局 namespace 上。App 卸载后它依然存在，用户只能**重启手机**才能清除。

这把 §5.1 的挂载残留问题放大一个量级，直接冲突 [`STORAGE_MANAGEMENT.md`](STORAGE_MANAGEMENT.md) 的清理边界承诺。

### 4.4 进程父子关系断裂 — 直接打穿现有进程托管

`ProcessRegistryImpl.stop()` / `stopAll()` 走 `session.close()` → `Process.destroy()`，配合 PRoot 的 `--kill-on-exit`，完全依赖 Java `Process` 的父子关系。

走 su 之后，手里的 `Process` 只是 su 客户端，真正的沙箱进程树 parent 是 magiskd：

- 客户端被杀，特权进程树可能存活成孤儿，继续持有挂载点与文件锁。
- `HostProcessRunner.stopProcess()` 的 `destroy()` / `destroyForcibly()` 对它无效。
- 需自行实现 PID/PGID 记账 + `su -c "kill -TERM -<pgid>"` 带外清理，且必须面对 App 被 LMK 直接杀掉、根本没机会执行清理的情况。

### 4.5 `pivot_root` 的实现前提

`pivot_root` 要求新根本身是一个 mount point，而 `/data/data/.../rootfs` 只是普通目录。必须先 `mount --bind rootfs rootfs` 把它变成挂载点。

### 4.6 `/data` 的 nosuid

Android 对 `/data` 施加 nosuid，chroot 内 setuid 位（如 `sudo`）不生效。实际影响有限（已经是 uid 0），但发行版内依赖 setuid 的包会有行为差异。要改需 `mount -o remount,suid /data`，属于影响整机的宿主级改动，**否决**。

---

## 5. 💸 工程代价清单（对现有已调通代码的冲击）

### 5.1 挂载从无状态变为有状态

`ProotCommandBuilder` 的 `-b` 是纯用户态路径翻译，进程一死零残留。chroot 下这些必须是真实 `mount --bind` 并逐个卸载：

```
-b /dev  -b /proc  -b /sys
-b <tmpDir>:/tmp            -b <workspaceDir>:/workspace
-b <homeDir>:/root          -b <optDir>:/opt/tianyan
-b <attachmentsDir>:/attachments
-b <rootfs>/.l2s:<rootfs>/.l2s
ProotMountLayout.hostSystemPaths (一批 Android 系统路径)
addStorageMountBindings(...)  (用户授权的共享存储)
```

任一次异常退出（Android 杀进程、卸载流程中断）都会留下宿主挂载点，用户侧表现为"存储清不掉、App 卸不干净、共享存储被占用"。`StorageManager` 的六类空间清理需全部重做。

### 5.2 假 root 变真 root，文件所有权语义翻转

现在 `--change-id=0:0` 是伪造的，guest 内 `chown` 落到宿主仍是 app uid，所以 `WorkspaceFileService`、`AndroidFtpServer`、`SshServiceManager` 都能正常读写工作区。chroot 下 uid 0 是真的，guest 内 `chown root:root` 之后 App 自身（uid 10xxx）可能反而读不了自己的工作区文件。

### 5.3 一批自愈逻辑分叉成两套

[`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) §6 的 PRoot 专属防御在真实 ext4 + 真 root 下全部不需要：

| 现有机制 | chroot 下 |
| :--- | :--- |
| `--link2symlink` + `.l2s` backing store | 不需要（原生 hardlink） |
| `/usr/local/sbin/tianyan-fix-perl` 幽灵硬链接自愈 | 不需要 |
| `dpkg.cfg.d/01_tianyan_nodoc` | 仅剩省 I/O 意义 |
| Git `safe.directory = *` | 语义变化（真实 uid 一致） |
| `--kernel-release` 伪造 | 不可用（内核真实版本） |

这不是"少干活"，而是 `EnvironmentDoctor`、`EnvironmentRepairer`、`RuntimeHealthChecker` 都要分叉两条判断路径，测试面直接翻倍。

### 5.4 chroot 不提供隔离

chroot 只是换根目录，不是容器；经典 fd 逃逸对 root 进程始终有效。**它只能当性能优化，不能当安全边界。** 若目的是增强隔离性，方向是反的。

### 5.5 风险放大与产品定位冲突（决定性理由）

chroot 内执行的是 **AI Agent 自动生成的命令**（`HarnessLoop` → `ToolExecutor` → 沙箱）。

- PRoot 下最坏后果被 Android 沙箱兜住，限于 App 私有目录 + 用户显式授权的共享存储。
- 真 root chroot 下，一条错误的 `rm -rf` 或 `dd` 可直接砖机。

对"Agent 循环自主执行"的产品定位，这个风险放大倍数不可接受。这是本 ADR 否决 chroot 的**首要理由**，性能收益不足以抵偿。

---

## 6. 🧪 若未来实施：唯一可接受的最小形态

三条硬约束，缺一不做：

1. **用 `unshare --mount --pid --fork` + `pivot_root`，不用裸 chroot。**
   - mount namespace 随进程树退出由内核自动回收，同时解决 §4.3 全局污染与 §5.1 挂载残留。
   - PID namespace 让 guest 内的 `ps` / `kill` 打不到宿主进程（裸 chroot 下 bind 进去的 `/proc` 会让 guest 看见并能杀掉整机进程）。

2. **抽 `SandboxLauncher` 接口，两个平行实现。**
   ```
   runtime/sandbox/SandboxLauncher.kt          (interface)
   runtime/proot/ProotLauncher.kt              (现有 ProotCommandBuilder 收敛于此)
   runtime/sandbox/NamespaceLauncher.kt        (unshare + pivot_root)
   ```
   **严禁**在 `ProotCommandBuilder` 内塞 `if (mode == ROOT)`。参照 [`ARCHITECTURE_RULES.md`](ARCHITECTURE_RULES.md) 的单一职责红线。

3. **默认关闭 + 自动降级。** 开发者页显式实验开关；任何环节失败复用 `PrivilegeManager` 现成的 `PrivilegeAvailability.DEGRADED` 语义回落 PRoot。

配套还需：PID/PGID 记账与带外清理（§4.4）、PTY 链路经 su 重接（§4.2）、`ProcessRegistry` 生命周期改造、`StorageManager` 清理边界重写。

---

## 7. 🔧 可立刻执行的前置动作（不依赖本 ADR 的实施决定）

### 7.1 验证 PRoot 的 seccomp 加速是否生效

Termux 版 PRoot 默认启用 seccomp 加速（只陷入需要翻译的 syscall），可能已吃掉相当一部分理论开销。用 `PROOT_NO_SECCOMP=1` 对照跑一次沙箱内 Gradle 构建，量化实际差距。**在拿到这个数字之前，任何新后端的收益估算都是猜测。**

### 7.2 审计 `-b` 绑定数量

`addHostSystemBindings()` 会遍历 `ProotMountLayout.hostSystemPaths` 逐个绑定，绑定数量直接影响 PRoot 路径翻译成本。核查是否存在可裁剪项。

### 7.3 修正 `checkRootPrivilege()` 的探测语义

`PrivilegeManager.checkRootPrivilege()` 现在只看 `su -c id` 是否返回 `uid=0`。但由 §4 可知**"root 可用" ≠ "namespace/chroot 可用"**：

- 用户可能使用未注入 permissive domain 补丁的裸 root；
- 可能在 KernelSU / APatch 中把天衍放进了排除（umount）列表；
- 内核可能裁剪了相关 namespace 支持。

若将来实施 §6，探测必须实际试跑最小动作（如 `su -c "unshare -m true"`）而非推断。**即使不实施，当前 `Authorized` 文案中"已释放原生 Linux 与内核硬件加速能力"的表述也超出了实际能力范围，建议校正。**

---

## 8. 📜 决策记录 (ADR 摘要)

- **ADR-S001**：PRoot 保持唯一沙箱执行后端。理由：无 Root 可用是核心产品定位；chroot 受益面仅限已 Root 设备，且 Shizuku 档完全无法受益。
- **ADR-S002**：裸 `chroot` 永久否决。理由：Android 上必须搭配全局 mount namespace 的 bind mount，残留需重启手机才能清除，冲突存储清理承诺。
- **ADR-S003**：否决的首要理由是**风险放大**而非工程成本。沙箱内执行的是 AI 自动生成的命令，真 root 下一条误操作可砖机；PRoot 的 Android 沙箱边界是该产品形态的必要保护。
- **ADR-S004**：SELinux **不是**本议题的主要障碍（su 的 permissive domain 已解决）。真正的障碍依次是 seccomp 继承载体、mount namespace 归属、进程父子关系断裂。
- **ADR-S005**：若未来实施，形态固定为 `unshare --mount --pid` + `pivot_root`，经 `SandboxLauncher` 接口并行实现，默认关闭且失败自动降级。
- **ADR-S006**：优先榨取 PRoot 侧余量（seccomp 加速验证、`-b` 绑定裁剪），在拿到量化数据前不排期新后端。

---

## 9. 🔗 关联文档

- 架构与模块拓扑：[`ARCHITECTURE.md`](ARCHITECTURE.md)
- 架构铁律：[`ARCHITECTURE_RULES.md`](ARCHITECTURE_RULES.md)
- 已知问题（PRoot 限制与硬链接防御）：[`KNOWN_ISSUES.md`](KNOWN_ISSUES.md)
- 存储分类与清理边界：[`STORAGE_MANAGEMENT.md`](STORAGE_MANAGEMENT.md)
- 核心执行时序：[`EXECUTION_TRACES.md`](EXECUTION_TRACES.md)
- 关键实现落点：
  - `runtime/proot/ProotCommandBuilder.kt` — PRoot 参数构建
  - `runtime/proot/ProotMountLayout.kt` — 宿主系统路径绑定清单
  - `runtime/shell/ProcessRegistry.kt` — 进程托管与生命周期
  - `runtime/privilege/PrivilegeManager.kt` — 三档模式探测与降级
  - `runtime/privilege/HostProcessRunner.kt` — su / Shizuku 宿主执行器
  - `runtime/pty/NativePtySession.kt` — forkpty 终端后端
  - `core/model/ExecutionMode.kt` — 模式定义

---

## 附录 A：容器后端（Droidspaces）的威胁模型与权限审计

> 本节由 2026-10-22 的上游源码审计得出，用于支撑「实验开关默认关」这一决策。
> 所有结论均给出上游文件与行号，可独立复算。

### A.1 为什么必须存在一个「特权发起者」

创建/进入 namespace 的操作（`unshare(CLONE_NEWNS)`、`mount`、`pivot_root`、`setns`）
都需要 `CAP_SYS_ADMIN`，即宿主 uid 0。**容器内的 root 是被授予的，不是容器自生的。**
因此必然存在一个持有宿主特权的发起者；本 PR 的 `su -c droidspaces …` 就是把它设成了
天衍自身。

### A.2 上游本来有「非特权客户端 + 特权 daemon」设计，但 App 用不了

- `main.c:1001-1008`：先尝试代理给 daemon，`proxy_ret != -2` 即 `goto cleanup` 返回；
- `main.c:1011`：`if (!is_no_root_cmd && getuid() != 0)` 这个 root 门禁**只有代理失败才走到**；
- `daemon.c:1126` → `ds_peer_authorized(conn, "droidspaces")`；
- `utils.c:2282-2298`：要求同 PID namespace，且（`cred.uid == 0` 或属于 `droidspaces` 组）。

但 Android 侧还有 SELinux 这一层，而它**没有给普通 App 开这个口**：

- `post-fs-data.sh` 以 `chcon u:object_r:droidspacesd_exec:s0` 起 daemon，运行在独立域
  `u:r:droidspacesd:s0`（并有 `permissive droidspacesd`）；
- `boot-module/sepolicy.rule` 中与 App 相关的授权只有
  `allow untrusted_app droidspacesd fd use` 与
  `allow untrusted_app su unix_stream_socket { read write }`；
- **不存在** `allow untrusted_app droidspacesd unix_stream_socket …`。

⇒ 普通 App 连不上 droidspacesd 的 socket；**App 侧唯一被放行的特权通道是 `su`**。
「加组就能免 root」在 Android 上不成立（组校验能过，SELinux 过不去）。

### A.3 容器隔离不是安全边界

上游 README 的 Security 一节原文：

> Root inside a container is powerful, and a hostile root process can reach the host.
> Do not run code you do not trust in a container.

即：PRoot 提供的是**假 root 沙箱**（宿主侧无真特权），Droidspaces 提供的是**真 root 容器**。
就「沙箱内 root 是否可能逃逸到宿主」而言，**PRoot 反而比 Droidspaces 更严**。
本 PR 的价值在于原生语义与性能，不在于更强的隔离。

### A.4 因此：开关是产品开关，不是安全边界

`containerBackendEnabled` 默认关，用于**避免改变现有 ROOT 用户的行为**（ADR-S001），
并让功能以实验形态逐步验证。

必须明示的局限：天衍内部具备 GUI 自动化能力，**任何位于应用内的开关都可能被自动化翻转**，
所以它**不构成针对「应用内智能体越权」的安全边界**。若后续需要真正的安全边界，
应把它放在应用进程之外（例如由系统/模块侧的策略决定是否授予该能力），
而不是依赖一个可被 UI 操作翻转的偏好项。

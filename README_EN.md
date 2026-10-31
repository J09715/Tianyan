<p align="center">
  <img src="app/src/main/res/drawable/tianyan_logo.webp" width="96" alt="Tianyan Logo" />
</p>

<h1 align="center">Tianyan · 天衍</h1>

<p align="center"><strong>The Myriad Manifestations in the Great Void.</strong></p>

<p align="center">
  Android No-Root Linux Runtime · AI Agent Harness · Visual Workflows · Native PTY Terminal · Mobile Dev Workspace
</p>

<p align="center">
  <code>Source v1.0.0</code> · <code>Android 10+ (API 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin 2.4.21</code> · <code>Jetpack Compose</code>
</p>

<p align="center">
  <a href="https://github.com/J09715/Tianyan/releases">Releases</a> ·
  <a href="https://github.com/J09715/Tianyan/issues">Issues</a> ·
  <a href="README.md">简体中文</a> · <strong>English</strong>
</p>

---

## 🌌 What is Tianyan?

**Tianyan (天衍)** takes its name from GuiXu, the vast ravine described in *Liezi*. It brings a **runnable, observable, recoverable, and extensible** Linux and AI automation environment to Android within the app's permissions.

Agents, MCP tools, Linux processes, native PTY terminals, browsers, Git, and project workspaces work together to turn natural language goals into files, running processes, verified code, and Android / Flutter build artifacts. Visual workflows make repeatable tasks persistent, schedulable, and subject to approval.

```text
Human intent -> Agent planning -> Linux / tools / MCP / browser / host actions -> Verify and deliver
                       ^                                                       |
                       +------------- Continue, approve when needed -----------+

Persistent workflows -> Conditions / parallel nodes / schedules / background runs / approvals
```

---

## ⚡ Core Capabilities

| Domain | Capabilities |
| :--- | :--- |
| **Linux Runtime** | PRoot runs ARM64 Linux without Android Root. OCI RootFS downloads with integrity checks, multiple distributions, switching, rollback, persistent directory bindings, and shared storage mounts. |
| **Agent Harness** | OpenAI-compatible Chat Completions, OpenAI Responses, and Anthropic Messages. Streaming text and reasoning output, function calls, vision input, token usage, and context compaction. |
| **Recovery and History** | Persistent sessions, task plans, semantic memory, context compaction, large tool output spill, and interrupted-operation recovery. SessionFork branches, file checkpoints, external-edit conflict detection, and undo for the most recent rewind. |
| **Subagents** | Multi-subagent scheduling, structured result adjudication, token budgets, write-path leases, and paginated result spill. |
| **Planning and Execution** | Separate Planner and Executor lanes with dependency-aware scheduling and step status. AI Roundtable provides parallel reviews from multiple perspectives and a consolidated report. |
| **On-Demand Capabilities** | Discover and invoke host/MCP capabilities through `use_capability`, including JavaScript orchestration. Nested calls retain argument checks, permission policies, approvals, deadlines, cancellation, and bounded audit metadata. |
| **Visual Workflows** | Persistent Kotlin DAGs with conditional edges and parallel nodes. Bash, background processes, agents, subagents, builds, host actions, and human approvals; run history, notifications, and WorkManager one-time, interval, or daily schedules. |
| **Native PTY** | JNI `openpty + fork + setsid + TIOCSCTTY + execve` backend in `libpty_native.so`. Termux terminal-emulator / TerminalView, job control, Ctrl+C, resize, multiple sessions, and a `script` fallback. |
| **Mobile Workspaces** | Empty projects, ZIP import with Zip Slip checks, GitHub clone, source trees, line diffs, background Gradle / Flutter builds, APK signing, and installation. |
| **Git Workbench** | JGit-based changes, staging, commits, branches, commit graphs, tags, push/pull, push preview, AI commit messages, and encrypted HTTPS tokens. |
| **Browser Automation** | Multi-tab WebView pool, Browser MCP, page hooks, CDP breakpoints, Worker-level Fetch interception, network timelines, and debug state. |
| **MCP and Tools** | Browser, SQLite, Git, APK audit, CodeGraph, and WebSearch presets. STDIO, common Streamable HTTP request/response flows, legacy SSE, and OAuth Authorization Code + PKCE. |
| **Plugin Platform** | Native page lifecycles, compiled-in Harness extensions, v1-compatible `.txplugin` v2 PROCESS packages, and importable static WEB packages. Web pages support capability approval and page-owned PRoot static services. |
| **Host Automation** | Wireless ADB discovery and notification pairing, Android / PRoot logs, device diagnostics, and Intent inspection. Authorized ADB, Shizuku, Root, accessibility, and virtual-screen tooling. |
| **Device Collaboration** | Floating assistant, temporary-PIN LAN WebChat, FTP transfer, foreground services, WakeLock, and Wi-Fi Lock support for long tasks. |
| **Native Interactive UI** | Agent-produced A2UI through `render_surface`, rendered in Compose with interactive forms, recent surfaces, and examples. |
| **Backup and Migration** | Local ZIP backups for sessions, memories, plans, skills, attachments, and portable preferences. Separate model-profile JSON import preview, copy/update, export, and system sharing. |

> This README describes source version `v1.0.0`. Published packages and their corresponding features are listed on [Releases](https://github.com/J09715/Tianyan/releases).

---

## 🧠 Agent Reliability and Permissions

- **Approval policies**: Session authorization and global policy remain separate. Plan mode restricts tool use to read-only operations; MCP annotations inform risk assessment. Discovered and scripted calls pass through the same checks.
- **Durable execution**: Execution intents, results, and operation states support recovery. Pending approvals retain the specific call, and resumed execution checks permissions again.
- **Branch consistency**: Model context follows the active session branch, excluding abandoned branches.
- **Context management**: Automatic/manual compaction, overflow replay, token budgets, and file-backed large outputs.
- **Request diagnostics**: Inspect recently constructed main-session model requests from the context usage panel. Redacted previews support search and copy and remain in memory only.
- **File recovery**: Checkpoints before turns, external-edit conflict reporting during rewind, and conditional undo of the most recent file restoration.
- **Controlled parallel work**: Subagent write leases and structured results reduce conflicting edits.
- **Credential storage**: Model keys, Git tokens, and OAuth tokens use app-side secure storage.

A script stops at an inner call that requires approval. Approval applies to that call; the agent can continue the remaining steps afterward. See [Security Surface](docs/SECURITY_SURFACE.md) for execution and credential boundaries.

---

## 🚀 Quick Start

1. **Install** an APK from [Releases](https://github.com/J09715/Tianyan/releases) on an ARM64 Android 10+ device. Release builds include SHA-256 files for verification.
2. **Initialize Linux** through the onboarding wizard. Ubuntu 24.04 LTS is the default. RootFS downloads require network access and additional storage; they are not bundled in the APK.
3. **Configure a model** in Settings using a supported API and your credentials. Compatible local endpoints such as llama.cpp / Ollama can also be connected.
4. **Create a workspace** in the Workshop, or import a GitHub repository / local ZIP. Describe a goal in Chat to edit code, run tools, test, and build. Use workflows for repeated tasks.
5. **Enable optional capabilities** such as MCP, wireless ADB, overlay, notifications, APK installation, Shizuku, or accessibility when needed.

Main entries:

- **Chat (智枢)**: Agent conversations, plans, tool calls, subagents, and code changes.
- **Terminal**: PTY sessions in the active Linux distribution.
- **Workshop (工坊)**: Projects, source browsing, diffs, builds, and APK delivery.
- **Workflows / Git / Browser / Wireless ADB**: Automation, repository management, web debugging, and authorized device diagnostics.
- **乾坤 → 特色功能**: One-sentence app creation, AI Roundtable, Morning Sentinel, LAN WebChat, and A2UI surfaces.
- **Settings backups and model profiles**: Export/restore local data and migrate model configuration.
- **Settings → App feature plugins → External Web plugins**: Import, open, and remove `.txplugin` Web page packages.

Notifications and exemption from battery optimization help long-running tasks. Android background restrictions can still delay or interrupt execution.

### Backup scope

Local backups preview records to add and preserve existing sessions. They exclude RootFS, actual workspace files, API keys, and custom request headers. Back up Linux and project files separately. Model-profile JSON also omits credentials by default; explicitly including them writes them as plaintext. See [Local Backup](docs/LOCAL_BACKUP.md) and [Model Profile Transfer](docs/MODEL_PROFILE_TRANSFER.md).

---

## 🐧 Distribution Catalog

The repository currently lists these 10 configurations:

| Distribution | Configured version |
| :--- | :--- |
| Ubuntu | 24.04 LTS, default |
| Debian | 12 (Bookworm) |
| Kali Linux | Rolling |
| Arch Linux | Rolling (ARM) |
| Fedora | 40 |
| Alpine Linux | 3.19 |
| AlmaLinux | 9 |
| Rocky Linux | 9 |
| openSUSE | Tumbleweed |
| Manjaro | Rolling |

RootFS installation prefers OCI images; some distributions support LXC image fallback under automatic routing. Installation availability and compatibility depend on upstream ARM64 artifacts, network access, and PRoot.

---

## 🔌 Model and Tool Protocols

**Models**: OpenAI-compatible Chat Completions, OpenAI Responses, Anthropic Messages, SSE text/reasoning streams, native function calling, JSON text tool calls, vision input, usage accounting, and model-specific context/compaction budgets.

**MCP**: STDIO JSON-RPC, common Streamable HTTP request/response flows, legacy SSE, OAuth Authorization Code + PKCE with refresh and callbacks, built-in presets, on-demand discovery, and delayed connection. Tool annotations participate in approval policy.

---

## 🧩 Plugin Platform and Web Pages

| Form | Current support | Boundary |
| :--- | :--- | :--- |
| **Native plugins** | Compose pages, lifecycle management, and Harness extensions assembled at build time | Trusted code shipped in the APK; no external Dex or Kotlin loading. |
| **PROCESS packages** | `.txplugin` v1 compatibility and v2 identity, API/dependency versions, resource declarations, SHA-256 checks, and installation receipts | Run in Linux. Scripts retain existing PRoot permissions; declarations do not isolate arbitrary scripts. |
| **WEB packages** | Import, open, upgrade, and remove static HTML/CSS/JS pages with a fixed `index.html` entry | Independent WebView origins and implemented host capabilities; no arbitrary network, shell, or file access. |

Import Web packages through **Settings → App feature plugins → External Web plugins**. The default `PACKAGE` source needs no Linux. Each page allows up to 63 files and 2 MiB of assets, verified on import and open. Version directories cannot be overwritten; upgrades and removal invalidate old pages and grants.

Supported capabilities are **APP_INFO** (basic app information) and **CLIPBOARD_WRITE** (plain text). Import grants no permissions. Calls require native confirmation; clipboard writes show the exact text and require confirmation each time, without reading the previous content. Host credentials are short-lived, single-use, bound to the current document, and never exposed to JavaScript.

Generate importable examples from the repository root:

```powershell
.\tools\package-web-example.ps1                    # build/examples/web-info.txplugin
.\tools\package-web-example.ps1 -Clipboard         # build/examples/web-clipboard.txplugin
.\tools\package-web-example.ps1 -Proot             # build/examples/web-info-proot.txplugin
```

The `PROOT_STATIC` source starts a fixed host-managed Python static server when opening a page and releases that instance on exit. It requires ready local Linux and `/usr/bin/python3`, without automatic dependency installation. The complete startup packet is limited to 4 KiB and checked during preview and import, making this suitable for small static pages. Combine `-Clipboard -Proot` for the clipboard example.

The platform remains in development: arbitrary PROCESS service pages, dynamic APIs/WebSockets, migration of the legacy shared HostBridge key, and developer full-access mode are pending. Local tests and Debug builds pass; real-device PRoot/WebView interaction still needs validation. See [Platform Progress](docs/PLUGIN_PLATFORM.md), [v2 Package Protocol](docs/PLUGIN_PACKAGE_V2.md), and [Web Extensions](docs/PLUGIN_WEB_EXTENSIONS.md).

---

## 🛠️ Build from Source

### Requirements

- **JDK 17**: Project and CI baseline; Android Studio JBR is also used for local builds.
- **Android SDK**: compileSdk 37.1 / targetSdk 37 / minSdk 29.
- **Gradle Wrapper**: 9.8.1.
- **Android Gradle Plugin**: 9.4.1.
- **Kotlin**: 2.4.21; **Compose BOM**: 2026.09.00.
- Complete Git checkout, including the local Maven AAR repository and prebuilt ARM64 native libraries.

Normal APK builds use the checked-in native artifacts. Rebuilding native PTY components additionally requires Android NDK `30.0.15729638`, CMake `3.22.1`, and Ninja.

### Windows

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

.\gradlew.bat architectureCheck --console=plain
.\gradlew.bat test --console=plain
.\gradlew.bat assembleDebug --console=plain
```

### macOS / Linux

```bash
export JAVA_HOME="/path/to/jdk-17"

./gradlew architectureCheck --console=plain
./gradlew test --console=plain
./gradlew assembleDebug --console=plain
```

Debug output:

```text
app/build/outputs/apk/debug/tianyan-v1.0.0-debug.apk
```

Only run the following if the bundled `app/src/main/jniLibs/arm64-v8a/libproot*.so` files are missing, fail validation, or need refreshing:

```powershell
.\tools\prepare-proot-runtime.ps1
```

For **TianyanDev**, which uses `top.tianyan.app.dev` and can coexist with the release package `top.tianyan.app`:

```powershell
$env:TIANYAN_DEV_BUILD="1"
.\gradlew.bat assembleDebug --console=plain
```

Without this variable, the normal Debug package is `top.tianyan.app.debug`.

The LAN WebChat frontend lives in `webchat/` and uses React, TypeScript, and Vite. In that directory, use pnpm 10.28.0 to run `pnpm install --frozen-lockfile`, followed by `pnpm dev`, `pnpm test`, or `pnpm build`.

See [Build, Test, and Debug Commands](docs/COMMANDS.md) for more options.

---

## 📐 Module Topology

```text
Tianyan/
├── app/                  # Host shell, Koin wiring, Manifest, JNI, foreground services
├── baselineprofile/      # Baseline profiles and startup benchmarks
├── core/
│   ├── model/            # Pure Kotlin domain models
│   ├── common/           # Dispatchers, logging, shared infrastructure
│   ├── database/         # Room sessions, messages, memory, workflows, OAuth, history
│   ├── datastore/        # Preferences, appearance, mounts, onboarding state
│   ├── network/          # OkHttp, SSE, network policies
│   ├── security/         # Keys, tokens, secure storage
│   └── browser/          # Shared browser models and protocols
├── runtime/              # PRoot, RootFS, PTY, processes, storage, workspaces
│   └── browser/          # WebView pool, hooks, CDP, network timelines, Browser MCP
├── project-template/     # Templates, dynamic forms, materialization
├── harness/              # Agent loop, providers, tools, approvals, subagents, MCP
│   └── core/             # Pure Kotlin turn interpreter and execution contracts
├── tools/                # Registry, recipes, installation, providers, backups
├── plugin/
│   ├── api/              # Pure Kotlin descriptors, extension contracts, catalog
│   ├── runtime/          # Lifecycles, generations, resource leases, page grants
│   ├── packages/         # v1/v2 protocol, versions, dependencies, resource checks
│   └── ui/               # Compose page hosts and restricted WebView adapter
├── showerclient/         # Virtual-screen client and display
├── webchat/              # LAN collaboration frontend: React / TypeScript / Vite
└── feature/              # Jetpack Compose features
    ├── theme/            # Themes and visual system
    ├── components/       # Shared components, icons, guided onboarding
    ├── navigation/       # Navigation3 routes
    ├── home/             # Runtime dashboard and diagnostics
    ├── chat/             # Chat, plans, diffs, floating assistant
    ├── terminal/         # Terminal UI and multiple sessions
    ├── workspace/        # Projects, source, builds, delivery
    ├── browser/          # In-app browser UI
    ├── workflow/         # DAG editor, runs, approvals, schedules
    ├── git/              # JGit workbench
    ├── a2uipoc/          # Native A2UI rendering and interaction
    ├── settings/         # Models, MCP, ADB, plugins, settings
    ├── developer/        # Developer sandbox and diagnostics
    ├── custom_iteration/ # Custom iteration capabilities
    └── onboarding/       # Initial setup and RootFS installation
```

The Android app mainly uses Kotlin and Compose, with C/JNI, Python, JavaScript, Shell, and TypeScript components.

---

## 📚 Documentation

Most engineering documents are in Chinese:

- [AI Navigation](docs/AI_NAVIGATION.md), [Architecture](docs/ARCHITECTURE.md), [Execution Traces](docs/EXECUTION_TRACES.md), and [Architecture Rules](docs/ARCHITECTURE_RULES.md).
- [Workflows](docs/WORKFLOW.md) and [Browser Design](docs/BROWSER_DESIGN.md).
- [Plugin Development](docs/PLUGIN_DEVELOPMENT_GUIDELINES.md) and [Android Offline Plugins](docs/ANDROID_OFFLINE_PLUGIN.md).
- [Plugin Platform Progress](docs/PLUGIN_PLATFORM.md), [v2 Package Protocol](docs/PLUGIN_PACKAGE_V2.md), and [Web Extensions and Examples](docs/PLUGIN_WEB_EXTENSIONS.md).
- [Storage Management](docs/STORAGE_MANAGEMENT.md), [Local Backup](docs/LOCAL_BACKUP.md), and [Model Profile Transfer](docs/MODEL_PROFILE_TRANSFER.md).
- [Request Context Diagnostics](docs/CONTEXT_DIAGNOSTICS.md) and [Security Surface](docs/SECURITY_SURFACE.md).
- [Sandbox Backend ADR](docs/ADR_SANDBOX_BACKEND.md), [Known Issues](docs/KNOWN_ISSUES.md), and [Commands](docs/COMMANDS.md).

---

## ⚠️ Permissions and Limitations

- **ARM64 only**: The supported ABI is `arm64-v8a`.
- **PRoot boundary**: Guest processes use the app uid and share the host network. PRoot provides Linux compatibility without KVM, real Root privileges, or kernel modules. It does not securely isolate malicious code; guest code holding the HostBridge key can invoke currently authorized host capabilities.
- **Network and storage**: Initial RootFS setup downloads images and requires additional disk space.
- **Host permissions**: Wireless ADB, Shizuku, Root, and accessibility provide different permission levels and require authorization.
- **Android background limits**: Foreground services and WorkManager help, but Doze, vendor battery policies, and service time limits can still delay or interrupt tasks.
- **LAN services**: Enable WebChat and FTP on trusted networks and disable them after use.
- **Device compatibility**: Complex TUIs, keyboard shortcuts, and heavy compilation depend on device resources and PRoot compatibility.
- **Credentials**: Keep API keys and remote credentials private and out of public repositories.

---

## 🤝 Contributing

Issues, pull requests, and real-device reports are welcome. Read [AI Navigation](docs/AI_NAVIGATION.md) and the relevant module documentation before changing code, then run architecture checks and applicable tests.

---

## 📜 Footnote

> Mount Sumeru is contained within a mustard seed; the Great Void is contained within the palm.

Constraints remain, but understanding them gives us room to build and verify our own world.

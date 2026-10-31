# 🛠️ 天衍 (Tianyan) — 常用开发与自动化命令速查 (Runbook & Commands)

---

## 1. 构建环境要求 (Prerequisites)

- **Android SDK**：主工程需要 Android SDK Platform 37.1（A2UI alpha 依赖要求）；targetSdk 仍为 37。已接受 SDK 许可证时，Gradle 可自动安装缺失的平台。

- **JDK 环境变量设置（Windows PowerShell）**：
  必须通过设置 `JAVA_HOME` 指向 Android Studio 自带的 JBR（支持 Java 25 / 17+）执行 Gradle 任务：
  ```powershell
  $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  # 或自定义安装路径（例如 D:\Program Files\Android\Android Studio\jbr）
  ```

---

## 2. 核心构建与测试命令 (Build & Test)

```powershell
# 1. 运行所有单元测试（全库回归）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat test --console=plain

# 2. 运行单模块单元测试
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :feature:settings:testDebugUnitTest --console=plain

# 3. 仅编译 Kotlin 代码（快速语法与类型检查）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin --console=plain

# 4. 单模块快速编译验证
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :harness:compileDebugKotlin --console=plain -q

# 5. 编译并打包 Debug APK
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug --console=plain

# 6. 验证架构依赖边界（规则唯一事实源：根目录 architecture-policy.json）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat architectureCheck --console=plain
# 检查内容：模块依赖白名单（含反向依赖）、依赖图无环、import 黑名单（模型层纯 Kotlin / DAO 直连）、
#          文件尺寸棘轮（maxFileLines=400，存量超限文件登记在 .architecture-baseline.json，只许缩减）

# 6.1 同步尺寸棘轮基线（收缩下调 / 拒绝上涨 / 清理失效条目；代码缩减后运行以下调基线）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat architectureBaselineSync --console=plain
# 注意：新增模块或调整模块依赖时必须同步登记 architecture-policy.json 的 requires，否则门禁失败

# 6.2 只读检查基线收紧与已偿还技术债回涨（需要 Python 3.9+；不改写基线）
# HEAD 用于比较本地未提交变更；PR 中必须使用可信目标分支的 commit SHA。
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat architectureBaselineCheck -ParchitectureBaselineBaseRef=HEAD --console=plain
# 可用 -ParchitecturePython=<python路径> 指定解释器。无 base ref 时仅核验当前额度并提示可收紧条目。
python tools/tests/test_architecture_baseline.py
python tools/tests/test_runtime_benchmark.py

# 6.3 三协议 SSE 离线 fixture 回放（普通响应及 1/7 字节 HTTP 分片；无需 API Key）
.\gradlew.bat :harness:testDebugUnitTest --tests "top.tianyan.app.harness.SseFixtureReplayTest" --console=plain

# 6.4 v2 插件作者校验/打包（纯 JVM，无 API Key、无设备；详见 PLUGIN_DEVELOPMENT_GUIDELINES.md）
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :plugin:cli:installDist --console=plain
.\plugin\cli\build\install\tianyan-plugin\bin\tianyan-plugin.bat check .\my-plugin
.\plugin\cli\build\install\tianyan-plugin\bin\tianyan-plugin.bat pack .\my-plugin --write-manifest
.\plugin\cli\build\install\tianyan-plugin\bin\tianyan-plugin.bat inspect .\dist\my-plugin-1.0.0.txplugin

# 7. 仅验证 Harness 内置工具契约与执行策略
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :harness:testDebugUnitTest --tests "top.tianyan.app.harness.*" --console=plain
```

---

## 3. 设备部署与实时日志调试 (Deploy & Debug)

```powershell
# 1. 安装 Debug APK 到已连接的真机或模拟器
adb install -r app/build/outputs/apk/debug/tianyan-v0.5.0-debug.apk

# 2. 启动天衍主入口 Activity
adb shell am start -n top.tianyan.app/.MainActivity

# 3. 实时过滤天衍运行时与智能体核心日志
adb logcat -s Tianyan:V HarnessLoop:V ProotProcess:V
```

## 4. Koin 依赖图回归

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "top.tianyan.app.di.*" --console=plain
```

修改依赖注册后同时运行完整单元测试和 `:app:assembleDebug`。依赖注入已迁移为 Koin DSL。

## 5. PTY 原生库重建与统一验收

项目使用版本管理中的 ARM64 PTY 预编译库。修改 `app/src/main/cpp/pty_native.c` 后，先用 Windows Android NDK 重建，再运行统一验收：

```powershell
.\tools\build-pty-native.ps1 -NdkRoot "$env:LOCALAPPDATA\Android\Sdk\ndk\30.0.15729638"
.\gradlew.bat test :app:assembleDebug architectureCheck --console=plain
```

脚本生成 16 KiB 对齐的 Android API 29 原生库及 `pty-native.properties`。C 源码、`app/src/main/jniLibs/arm64-v8a/libpty_native.so` 和清单应一起提交；打包前检查源码哈希、二进制哈希、全部 JNI 声明和 libc 依赖，拒绝未经重编译的源码或被替换的库。源码哈希统一按 LF 计算，不受 Windows/Git 换行转换影响。该检查不能代替真机 PTY 和 Termux 渲染验证。

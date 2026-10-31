# app module R8/ProGuard rules. Library-specific rules are supplied by each dependency.

# Keep generic type and nesting metadata used by serialization and reflective APIs.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Persisted polymorphic payloads may use their declared serializable class name. Keep only
# those class names while still allowing unused classes/members to shrink and optimize.
-keep,allowshrinking,allowoptimization @kotlinx.serialization.Serializable class top.tianyan.app.**

# JNI symbol lookup includes the declaring class and method names. This precise rule also
# covers zstd-jni native entry points without retaining the whole dependency.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class top.tianyan.app.runtime.pty.NativePty { *; }

# PrivilegeManager reflectively invokes Shizuku's private compatibility API.
-keepclassmembers,allowoptimization class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Harness resolves this app-layer service by a constant class name to avoid a module cycle.
-keepnames class top.tianyan.app.service.AgentForegroundService

# Keep line information for deobfuscating production crashes while hiding source filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Shizuku UserService (release 构建必保) ===
# Shizuku 在独立 fork 的进程中按全限定类名反射加载 ShizukuHostUserService，
# 并通过 AIDL 描述符 (DESCRIPTOR = 接口全限定名) 匹配 Binder 接口。
# R8 一旦重命名/裁剪这些类或其构造函数，UserService 进程会静默崩溃，
# 表现为 bindUserService 一直超时、服务端日志只有 addUserService 无进程启动。
-keep class top.tianyan.app.runtime.privilege.ShizukuHostUserService { *; }
-keep class top.tianyan.app.runtime.privilege.IShizukuHostService { *; }
-keep class top.tianyan.app.runtime.privilege.IShizukuHostService$* { *; }
# HostProcessRunner 由 ShizukuHostUserService 直接引用，R8 可达性分析应自动保留，
# 但因其运行在 Shizuku 独立进程中，显式保活避免边缘裁剪。
-keep class top.tianyan.app.runtime.privilege.HostProcessRunner { *; }

# === Shower 虚拟屏 Binder 交接（release 必保） ===
# shower-server.jar 与宿主跨进程传递 Parcelable / AIDL，parcel 里写的是**全限定类名**
# （com.ai.assistance.shower.ShowerBinderContainer）。R8 若重命名宿主侧这些类，
# 反序列化时 Class.forName 按原名找不到 → BadParcelableException(ClassNotFoundException)，
# 接收端崩溃、虚拟屏永远起不来。接口的 AIDL DESCRIPTOR 同样依赖全限定名。
-keep class com.ai.assistance.shower.** { *; }
-keep class com.ai.assistance.showerclient.ShowerBinderReceiver { *; }

# === JGit（feature:git 分支管理，release 必保） ===
# JGit 内部按类名反射加载签名/传输实现，且引用了 Android 不存在的 OSGi/javax 可选类。
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.osgi.framework.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

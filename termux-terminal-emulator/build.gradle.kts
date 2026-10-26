// Termux terminal-emulator v0.118.0 fork（GPL-3.0，来源 https://github.com/termux/termux-app）。
// 目的：在 TerminalSession 中抽出 TerminalSessionBackend 缝隙，使远程 SSH PTY 能复用原生渲染层。
// 包名与公共 API 必须与上游逐字一致，以保持与 terminal-view AAR 的二进制兼容（只允许新增）。
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "com.termux.terminal"
    // 纯 Java 源码 + 预编译 JNI（src/main/jniLibs/arm64-v8a/libtermux.so），无 Android 资源。
}

val bundledTermuxNative = layout.projectDirectory.file(
    "src/main/jniLibs/arm64-v8a/libtermux.so",
)
val termuxNativeManifest = layout.projectDirectory.file("termux-native.properties")

tasks.configureEach {
    if (name == "preBuild") {
        doFirst {
            check(bundledTermuxNative.asFile.isFile && bundledTermuxNative.asFile.length() > 4096L) {
                "Missing bundled libtermux.so. Extract jni/arm64-v8a/libtermux.so from " +
                    "repo/com/termux/terminal-emulator/0.118.0/terminal-emulator-0.118.0.aar."
            }
            check(termuxNativeManifest.asFile.isFile) { "Missing termux-native.properties manifest." }
            val manifest = Properties().apply {
                termuxNativeManifest.asFile.inputStream().use { load(it) }
            }
            val nativeBytes = bundledTermuxNative.asFile.readBytes()
            val nativeSha256 = MessageDigest.getInstance("SHA-256")
                .digest(nativeBytes).joinToString("") { "%02x".format(it) }
            check(manifest.getProperty("format.version") == "1" &&
                manifest.getProperty("abi") == "arm64-v8a") {
                "Incompatible termux-native.properties manifest."
            }
            check(nativeSha256 == manifest.getProperty("binary.sha256")) {
                "Bundled libtermux.so does not match its manifest. " +
                    "Expected ${manifest.getProperty("binary.sha256")}, got $nativeSha256."
            }
            // 符号兜底：libtermux.so 必须导出 JNI.java 声明的全部 native 方法。
            val nativeSymbols = nativeBytes.toString(Charsets.ISO_8859_1)
            val jniDeclarations = layout.projectDirectory.file(
                "src/main/java/com/termux/terminal/JNI.java",
            ).asFile.readText()
            Regex("""native\s+\w+\s+(\w+)\(""").findAll(jniDeclarations).forEach { method ->
                val symbol = "Java_com_termux_terminal_JNI_${method.groupValues[1]}\u0000"
                check(symbol in nativeSymbols) {
                    "Bundled libtermux.so is missing ${method.groupValues[1]}."
                }
            }
        }
    }
}
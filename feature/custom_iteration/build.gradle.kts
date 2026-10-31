plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.custom_iteration"
}

dependencies {
    // TianyanDevBuildCoordinator 通过 LinuxRuntime 标准契约在沙盒内执行 gh 命令
    implementation(project(":runtime"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.test.robolectric)
    // 验证 Koin 图能真正解析出 Coordinator 与 ViewModel（装配回归闸门）
    testImplementation(libs.koin.test)
}

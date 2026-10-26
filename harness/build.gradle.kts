plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "top.tianyan.app.harness"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":plugin:api"))
    implementation(project(":plugin:packages"))
    api(project(":harness:core"))
    api(project(":core:common"))
    api(project(":core:browser"))
    api(project(":runtime:browser"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(libs.koin.core)
    implementation(libs.okhttp)
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Bot 记忆仓库：宿主侧 JGit，不依赖沙箱是否安装 git
    api(libs.jgit)
    // codemode 脚本引擎：ClassShutter 全禁 Java、解释模式（-1 优化级别），仅暴露 capability 绑定
    implementation(libs.rhino)

    testImplementation(libs.bundles.test.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // 集成测试需要直接构建 in-memory Room 数据库（core:database 是 implementation 依赖不传递 Room）
    testImplementation(libs.bundles.room)
    testImplementation(libs.bundles.asm.test)
}

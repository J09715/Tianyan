plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.git"
    resourcePrefix = "fgit_"
}

dependencies {
    implementation(project(":core:security"))
    implementation(project(":runtime"))
    // ProviderClient：AI 生成 commit message 的单次非会话调用
    implementation(project(":harness"))
    implementation(project(":feature:theme"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    // JGit：MGit 同款 Git 实现，宿主侧直接操作工作区仓库
    implementation(libs.jgit)
}

plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.navigation"
}

dependencies {
    implementation(project(":plugin:ui"))
    implementation(project(":runtime"))
    implementation(project(":core:datastore"))
    implementation(project(":feature:theme"))
    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:workflow"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:developer"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:git"))
    implementation(project(":feature:bot"))
    implementation(project(":feature:a2uipoc"))
    implementation(libs.kotlinx.serialization.json)
    // miuix-navigation3-ui 提供 androidx.navigation3.ui.NavDisplay 的 MIUI/HyperOS 风格实现，
    // 默认转场即侧滑动画，直接用默认 transitionSpec，不写自定义转场
    implementation(libs.bundles.navigation3)
    testImplementation(libs.junit)
}

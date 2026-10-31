plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.chat"
}

dependencies {
    implementation(project(":feature:theme"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":harness"))
    implementation(project(":tools"))
    // A2UI PoC：render_surface 工具结果在聊天流内嵌渲染原生界面
    implementation(project(":feature:a2uipoc"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.haze)

    testImplementation(libs.bundles.test.robolectric)
}

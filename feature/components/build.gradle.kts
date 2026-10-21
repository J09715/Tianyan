plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.tianyan.app.feature.components"
    resourcePrefix = "components_"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // 空态/错误态里的展开收起动画：不走 material3 的传递路径，显式声明
    implementation(libs.androidx.compose.animation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":feature:theme"))
    // 澄明(液态玻璃)主题：底部导航毛玻璃折射
    implementation(libs.backdrop)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

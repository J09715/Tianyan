// Shower 虚拟屏客户端库（源自 AAswordman/Operit，LGPL-3.0）。
// 包名 com.ai.assistance.showerclient / com.ai.assistance.shower 参与 Binder 协议与
// 广播 Parcelable 契约，必须与 shower-server.jar 内的实现保持一致，禁止重命名。
plugins {
    alias(libs.plugins.tianyan.android.library)
}

android {
    namespace = "com.ai.assistance.showerclient"

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}

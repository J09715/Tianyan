plugins {
    alias(libs.plugins.tianyan.android.feature)
}

android {
    namespace = "top.tianyan.app.feature.terminal"
}

dependencies {
    implementation(project(":feature:theme"))
    implementation(project(":plugin:ui"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(libs.androidx.activity.compose)
    // Pair TerminalView with runtime's compatible fork; never package both emulator implementations.
    implementation(libs.termux.terminal.view) {
        exclude(group = "com.termux", module = "terminal-emulator")
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

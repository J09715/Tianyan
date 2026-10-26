plugins {
    alias(libs.plugins.tianyan.jvm.library)
}

dependencies {
    api(project(":plugin:api"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    alias(libs.plugins.tianyan.android.library)
    alias(libs.plugins.tianyan.android.room)
}

android {
    namespace = "top.tianyan.app.core.database"

    // 迁移测试（MigrationTestHelper）从测试资产读取导出的 schema JSON
    sourceSets {
        named("test") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.test.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(libs.bundles.asm.test)
}

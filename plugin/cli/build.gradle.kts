plugins {
    alias(libs.plugins.tianyan.jvm.library)
    application
}

dependencies {
    implementation(project(":plugin:packages"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

application {
    mainClass.set("top.tianyan.app.plugin.cli.MainKt")
    applicationName = "tianyan-plugin"
}

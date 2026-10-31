import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import top.tianyan.app.buildlogic.libs

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")

            extensions.configure<KspExtension> {
                arg("room.schemaLocation", "$projectDir/schemas")
                arg("room.incremental", "true")
            }

            dependencies {
                "implementation"(libs.findBundle("room").get())
                "ksp"(libs.findLibrary("androidx.room.compiler").get())
            }
        }
    }
}

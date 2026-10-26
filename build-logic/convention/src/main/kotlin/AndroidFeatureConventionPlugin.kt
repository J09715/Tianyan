import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import top.tianyan.app.buildlogic.libs

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "tianyan.android.library.compose")

            dependencies {
                "implementation"(project(":core:common"))
                "implementation"(project(":core:model"))
                "implementation"(project(":feature:components"))

                "implementation"(libs.findBundle("compose.ui").get())
                "implementation"(libs.findLibrary("androidx.lifecycle.runtime.compose").get())
                "implementation"(libs.findBundle("koin.compose").get())
                "implementation"(libs.findLibrary("kotlinx.coroutines.core").get())
            }
        }
    }
}

package top.tianyan.app.di.project.template

import org.koin.dsl.module
import top.tianyan.app.template.ProjectTemplateEngine
import top.tianyan.app.template.ProjectTemplateStore

/** Dependency registrations owned by the project-template module. */
val projectTemplateModule = module {
    single<ProjectTemplateEngine> { ProjectTemplateEngine(context = get()) }

    single<ProjectTemplateStore> { ProjectTemplateStore(context = get()) }
}

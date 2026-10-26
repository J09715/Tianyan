package top.tianyan.app.di.core.security

import org.koin.dsl.module
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.core.security.SecretRedactor

/** Dependency registrations owned by the core:security module. */
val coreSecurityModule = module {
    single<SecretManager> { SecretManager() }

    single<SecretRedactor> { SecretRedactor() }

    single<SensitiveDataRedactor> { get<SecretRedactor>() }
}

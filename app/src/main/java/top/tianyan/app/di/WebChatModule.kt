package top.tianyan.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.tianyan.app.runtime.webchat.WebChatAgentGateway
import top.tianyan.app.webchat.TianyanWebChatAgentGateway

@Module
@InstallIn(SingletonComponent::class)
abstract class WebChatModule {
    @Binds
    abstract fun bindWebChatAgentGateway(impl: TianyanWebChatAgentGateway): WebChatAgentGateway
}

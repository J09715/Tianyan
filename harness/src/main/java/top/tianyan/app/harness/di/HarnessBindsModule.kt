package top.tianyan.app.harness.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import top.tianyan.app.harness.checkpoint.ConversationRewinder
import top.tianyan.app.harness.checkpoint.SessionForkConversationRewinder
import top.tianyan.app.harness.prompt.DefaultPrivilegeSectionRenderer
import top.tianyan.app.harness.prompt.PrivilegeSectionRenderer
import top.tianyan.app.harness.projection.LiveMessagePort
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.redteam.RedTeamSkillSource
import top.tianyan.app.harness.redteam.RepositoryRedTeamSkillSource
import top.tianyan.app.harness.redteam.RedTeamSkillStore
import top.tianyan.app.harness.redteam.RepositoryRedTeamSkillStore

/** Harness 模块内的 Hilt 端点绑定：可测接缝在此收口。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HarnessBindsModule {

    @Binds
    @Singleton
    abstract fun bindPrivilegeSectionRenderer(
        impl: DefaultPrivilegeSectionRenderer,
    ): PrivilegeSectionRenderer

    /** 实时消息窄端口 → 会话消息投影器（CapabilityEventWriter / HarnessLoop 共用） */
    @Binds
    @Singleton
    abstract fun bindLiveMessagePort(
        impl: SessionMessageProjector,
    ): LiveMessagePort

    /** 对话回退 fork 处理器 → 会话树派生实现（RewindController 的可选注入点收口） */
    @Binds
    @Singleton
    abstract fun bindConversationRewinder(
        impl: SessionForkConversationRewinder,
    ): ConversationRewinder

    /** 红队技能来源 → 技能库实现（开工前体检据此判断「能不能跑」而不只是「列得出来」） */
    @Binds
    @Singleton
    abstract fun bindRedTeamSkillSource(
        impl: RepositoryRedTeamSkillSource,
    ): RedTeamSkillSource

    /** 技能库读写接缝 → 落库实现（控制台「技能库」页签的新建/编辑/删除）。 */
    @Binds
    @Singleton
    abstract fun bindRedTeamSkillStore(
        impl: RepositoryRedTeamSkillStore,
    ): RedTeamSkillStore
}

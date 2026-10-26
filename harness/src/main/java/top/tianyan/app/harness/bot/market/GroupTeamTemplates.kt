package top.tianyan.app.harness.bot.market

import top.tianyan.app.core.model.bot.*

object GroupTeamTemplates {
    val all = listOf(
        GroupTeamTemplate("sansheng", "三省六部", "太子协调，中书起草、门下审议、尚书调度六部；驳回后修改，争议交主控裁决。",
            listOf("taizi", "zhongshu", "menxia", "shangshu").map { TeamTemplateMember(it, "market.$it") } +
                listOf("libu-hr", "hubu", "libu", "bingbu", "xingbu", "gongbu").map { TeamTemplateMember(it, "market.$it", "shangshu") },
            "taizi", listOf(GroupReviewRule("plan-review", "方案审议与执行", "zhongshu", "menxia", "shangshu"))),
        GroupTeamTemplate("software", "软件交付团队", "产品起草、测试独立审议、研发协调前后端交付，文档归研发协调。",
            listOf(TeamTemplateMember("chief", "market.writer", name = "交付主控", role = "目标协调与争议裁决"), TeamTemplateMember("product", "market.product-manager"),
                TeamTemplateMember("review", "market.qa-engineer"), TeamTemplateMember("lead", "market.researcher", name = "研发协调", role = "研发派单与交付验收"),
                TeamTemplateMember("frontend", "market.frontend-engineer", "lead"),
                TeamTemplateMember("backend", "market.backend-engineer", "lead")),
            "chief", listOf(GroupReviewRule("delivery-review", "交付方案审议", "product", "review", "lead"))),
    ).also { templates -> templates.forEach { it.validate() } }
}

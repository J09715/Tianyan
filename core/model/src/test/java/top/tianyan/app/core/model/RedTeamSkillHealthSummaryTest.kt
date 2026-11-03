package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 技能体检汇总的纯逻辑测试。
 *
 * 背景：红队工作台在「未绑定目标」的新会话里打开就闪退。根因是 `skillHealth`
 * 用 `require(redTeamMode == "red_team")` 把「本会话是否已进入红队模式」当成体检前置条件，
 * 而用户点开工作台时正是还没绑定的时刻（模式仍是 off），于是必抛异常。
 *
 * 体检本身与模式无关，它只回答「这些技能现在能不能跑」。这里锁住汇总口径，
 * 保证在没有会话状态参与时也能算出正确结论。
 */
class RedTeamSkillHealthSummaryTest {

    private fun verdict(name: String, status: String) = RedTeamSkillAvailability.Verdict(
        name = name,
        status = status,
        issues = emptyList(),
        problems = emptyList(),
        needsUser = emptyList(),
        checked = RedTeamSkillAvailability.Checked(file = "/x/SKILL.md"),
    )

    @Test
    fun `全部可用时 ready 口径成立`() {
        val summary = RedTeamSkillAvailability.summarizeSkills(
            listOf(verdict("a", "available"), verdict("b", "available")),
        )
        assertEquals(2, summary["total"])
        assertEquals(2, summary["available"])
        assertEquals(0, summary["broken"])
    }

    @Test
    fun `混合状态分别计数`() {
        val summary = RedTeamSkillAvailability.summarizeSkills(
            listOf(
                verdict("a", "available"),
                verdict("b", "broken"),
                verdict("c", "unknown"),
                verdict("d", "broken"),
            ),
        )
        assertEquals(4, summary["total"])
        assertEquals(1, summary["available"])
        assertEquals(2, summary["broken"])
        assertEquals(1, summary["unknown"])
    }

    @Test
    fun `空技能库不崩溃且计数为零`() {
        // 未装任何红队技能时体检也要能返回，否则控制台一进来就抛异常
        val summary = RedTeamSkillAvailability.summarizeSkills(emptyList())
        assertEquals(0, summary["total"])
        assertEquals(0, summary["available"])
    }
}

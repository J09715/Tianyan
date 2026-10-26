package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhoneAgentActionTest {
    @Test
    fun parsesTapAfterThinking() {
        val action = parsePhoneAgentAction(
            """
            小红书已打开，现在需要点击搜索框
            do(action="Tap", element=[500, 100])
            """.trimIndent(),
        )
        assertEquals(PhoneAgentAction.Tap(500, 100), action)
    }

    @Test
    fun parsesSwipeAndFinish() {
        assertEquals(
            PhoneAgentAction.Swipe(100, 800, 100, 200),
            parsePhoneAgentAction("""do(action="Swipe", start=[100, 800], end=[100, 200])"""),
        )
        assertEquals(
            PhoneAgentAction.Finish("已发出"),
            parsePhoneAgentAction("""finish(message="已发出")"""),
        )
    }

    @Test
    fun parsesTypeAndMapsPoint() {
        assertEquals(
            PhoneAgentAction.Type("你好"),
            parsePhoneAgentAction("""do(action="Type", text="你好")"""),
        )
        assertEquals(0, phoneAgentPoint(0, 1080))
        assertEquals(1079, phoneAgentPoint(1000, 1080))
        assertEquals(539, phoneAgentPoint(500, 1080))
    }

    @Test
    fun parsesAnswerWrappedTap() {
        assertEquals(
            PhoneAgentAction.Tap(811, 188),
            parsePhoneAgentAction("""<answer>do(action="Tap", element=[811, 188])</answer>"""),
        )
    }

    @Test
    fun resolvesAppNameWithoutGuessing() {
        val apps = listOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ",
            "com.tencent.qqmusic" to "QQ音乐",
            "com.xingin.xhs" to "小红书",
        )
        assertEquals("com.tencent.mm", resolveLaunchablePackage("微信", apps))
        assertEquals("com.tencent.mobileqq", resolveLaunchablePackage("com.tencent.mobileqq", apps))
        assertEquals("com.tencent.mobileqq", resolveLaunchablePackage("QQ", apps))
        assertEquals("com.xingin.xhs", resolveLaunchablePackage("红书", apps))
        assertNull(resolveLaunchablePackage("Q", apps))
        assertEquals("com.not.listed", resolveLaunchablePackage("com.not.listed", apps))
    }

    @Test
    fun parsesSensitiveTapAndNote() {
        assertEquals(
            PhoneAgentAction.TakeOver("确认支付"),
            parsePhoneAgentAction("""do(action="Tap", element=[10, 20], message="确认支付")"""),
        )
        assertEquals(
            PhoneAgentAction.Note("订单已提交"),
            parsePhoneAgentAction("""<answer>do(action="Note", message="订单已提交")</answer>"""),
        )
        assertTrue(phoneAgentSystemPrompt(LocalDate.of(2026, 10, 9)).contains("do(action=\"Launch\""))
    }

    @Test
    fun rejectsUnknownAction() {
        assertNull(parsePhoneAgentAction("我先看看画面"))
        assertNull(parsePhoneAgentAction("""do(action="Fly")"""))
    }

    @Test
    fun ignoresThinkingAndNeverParsesCommandsInsideTypedText() {
        assertEquals(
            PhoneAgentAction.Type("请填写 finish(message=\"完成\")\n下一行"),
            parsePhoneAgentAction("""<think>do(action="Back")</think><answer>do(action="Type", text="请填写 finish(message=\"完成\")\n下一行")</answer>"""),
        )
        assertNull(parsePhoneAgentAction("""<think>finish(message="猜测完成")</think>"""))
        assertNull(parsePhoneAgentAction("""do(action="Back")
            finish(message="完成")"""))
        assertNull(parsePhoneAgentAction("""<answer>do(action="Back")</answer><answer>do(action="Home")</answer>"""))
    }

    @Test
    fun parsesWhitespaceSingleQuotesEmptyTextAndParameterOrder() {
        assertEquals(PhoneAgentAction.Type(""), parsePhoneAgentAction("""do ( text = '', action = 'Type' )"""))
        assertEquals(PhoneAgentAction.TakeOver("确认, 支付"),
            parsePhoneAgentAction("""do(message="确认, 支付", element=[10, 20], action="Tap")"""))
        assertEquals(PhoneAgentAction.Type("C:\\tmp\\a"),
            parsePhoneAgentAction("""do(action="Type", text="C:\\tmp\\a")"""))
        assertEquals(PhoneAgentAction.Wait(2500), parsePhoneAgentAction("""do(action="Wait", duration="2.5 seconds")"""))
    }

    @Test
    fun rejectsMalformedDuplicateAndOutOfRangeArguments() {
        listOf(
            """do(action="Tap", element=[999999999999999999999, 20])""",
            """do(action="Tap", element=[1001, 20])""",
            """do(action="Tap", element=[-1, 20])""",
            """do(action="Tap", start=[10, 20])""",
            """do(action="Type", text="hello""",
            """do(action="Type", text="a", text="b")""",
            """do(action="Wait", duration="100 seconds")""",
            """do(action="Wait", duration=[1, 2])""",
            """finish(message="done", action="Tap")""",
            """<answer>do(action="Back")""",
        ).forEach { raw -> assertNull(raw, parsePhoneAgentAction(raw)) }
    }
}

package top.tianyan.app.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkshopSigningDraftTest {

    @Test
    fun `blank prefix uses tianyan-release default pattern`() {
        val draft = generateDefaultSigningDraft("")
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

        assertTrue(draft.name.startsWith("tianyan-release-$dateStr-"))
        assertEquals("tianyan-release-key", draft.alias)
        // 口令必须是强随机：不再是「项目名#年份_4位」这种可枚举格式
        assertTrue("口令不应包含可枚举的项目名", !draft.storePassword.contains("Tianyan"))
        assertTrue("口令长度应为 24", draft.storePassword.length == 24)
        assertEquals(draft.storePassword, draft.keyPassword)
        assertEquals(25, draft.validityYears)
        assertEquals("Tianyan Developer", draft.organization)
        assertTrue("密码长度应 >= 20", draft.storePassword.length >= 20)
    }

    @Test
    fun `custom prefix is formatted cleanly into name, alias, password, and org`() {
        val draft = generateDefaultSigningDraft("mygame_pro")
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val yearStr = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())

        assertTrue(draft.name.startsWith("mygame-pro-$dateStr-"))
        assertEquals("mygame-pro-key", draft.alias)
        assertTrue("口令不应包含可枚举的项目名", !draft.storePassword.contains("MygamePro"))
        assertTrue("口令长度应为 24", draft.storePassword.length == 24)
        assertEquals(draft.storePassword, draft.keyPassword)
        assertEquals(25, draft.validityYears)
        assertEquals("MygamePro Developer", draft.organization)
    }

    /**
     * 口令必须真的随机。
     *
     * 这条是为了钉住曾经的真实缺陷：旧实现把口令拼成「项目名#年份_4位随机」，
     * 随机部分只有约 20 bits 熵，而项目名与年份都可枚举；它保护的是有效期 25 年的
     * 签名密钥库。这里从两个角度验证——多次生成不能重复、且必须包含多种字符类型。
     */
    @Test
    fun `generated passwords are strong and never repeat`() {
        val passwords = (1..200).map { generateDefaultSigningDraft("myapp").storePassword }
        assertEquals("200 次生成不应出现重复口令", passwords.size, passwords.toSet().size)
        passwords.forEach { pwd ->
            assertEquals("口令长度应为 24", 24, pwd.length)
            assertTrue("口令应含大写", pwd.any { it.isUpperCase() })
            assertTrue("口令应含小写", pwd.any { it.isLowerCase() })
            assertTrue("口令应含数字", pwd.any { it.isDigit() })
            // 关键：不含任何可从项目名/年份推断的片段
            assertTrue("口令不应包含项目名", !pwd.contains("Myapp", ignoreCase = true))
            assertTrue("口令不应包含当前年份", !pwd.contains(java.time.Year.now().value.toString()))
        }
    }

    @Test
    fun `store and key passwords are identical but both strong`() {
        val draft = generateDefaultSigningDraft("myapp")
        assertEquals(draft.storePassword, draft.keyPassword)
        assertTrue("密钥口令也要够长", draft.keyPassword.length >= 20)
    }

    @Test
    fun `repeated clicking strips prior date and hex suffix instead of compounding`() {
        val first = generateDefaultSigningDraft("myapp")
        val second = generateDefaultSigningDraft(first.name)

        assertTrue(second.name.startsWith("myapp-"))
        // Should not have double date tags like myapp-20260830-xxxx-20260830-yyyy
        assertEquals(1, Regex("\\d{8}").findAll(second.name).count())
        assertEquals("myapp-key", second.alias)
    }
}

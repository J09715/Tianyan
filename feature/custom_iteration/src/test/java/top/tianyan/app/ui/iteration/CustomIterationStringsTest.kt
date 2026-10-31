package top.tianyan.app.ui.iteration

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 自定义迭代界面的 i18n 资源一致性闸门（纯 JVM，不依赖 Android 资源系统）。
 *
 * 目的：
 * 1. 界面文案必须全部走 `stringResource`，中英两套资源键集合必须完全一致，避免某一语言
 *    漏翻后在运行时回退到默认语言或直接崩溃；
 * 2. 英文资源不得残留中日韩字符，防止复制中文文案后忘记翻译；
 * 3. 本模块新增的 15 个界面键必须两套都在，锁住本次重构的契约。
 *
 * 基准目录为模块根（Gradle 单元测试的默认工作目录），与 runtime 模块读取
 * `../assets` 的既有约定一致。
 */
class CustomIterationStringsTest {

    private val zh: Map<String, String> = readStrings("src/main/res/values/strings.xml")
    private val en: Map<String, String> = readStrings("src/main/res/values-en/strings.xml")

    private fun readStrings(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("资源文件缺失：$path（基准目录应在模块根）", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            result[element.getAttribute("name")] = element.textContent.orEmpty()
        }
        return result
    }

    @Test
    fun `zh and en expose exactly the same string keys`() {
        assertEquals(
            "中英资源键集合不一致：仅中文=${zh.keys - en.keys}，仅英文=${en.keys - zh.keys}",
            zh.keys.sorted(),
            en.keys.sorted(),
        )
    }

    @Test
    fun `every string value is non blank`() {
        val blankZh = zh.filterValues { it.isBlank() }.keys
        val blankEn = en.filterValues { it.isBlank() }.keys
        assertTrue("存在空白中文文案：$blankZh", blankZh.isEmpty())
        assertTrue("存在空白英文文案：$blankEn", blankEn.isEmpty())
    }

    @Test
    fun `english resources contain no CJK characters`() {
        // CJK 统一表意文字 + 中文标点（。，「」（）…）
        val cjk = Regex("[\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef]")
        val offenders = en.filterValues { cjk.containsMatchIn(it) }.keys
        assertTrue("英文资源仍含中文字符，需补翻译：$offenders", offenders.isEmpty())
    }

    @Test
    fun `custom iteration screen keys are present in both locales`() {
        val screenKeys = listOf(
            "iteration_title",
            "iteration_hero_title",
            "iteration_hero_body",
            "iteration_features_title",
            "iteration_features_subtitle",
            "iteration_feature_workspace_title",
            "iteration_feature_workspace_desc",
            "iteration_feature_skill_title",
            "iteration_feature_skill_desc",
            "iteration_feature_ci_title",
            "iteration_feature_ci_desc",
            "iteration_feature_dual_title",
            "iteration_feature_dual_desc",
            "iteration_preparing_workspace",
            "iteration_start",
        )
        val missingZh = screenKeys.filterNot { zh.containsKey(it) }
        val missingEn = screenKeys.filterNot { en.containsKey(it) }
        assertTrue("中文资源缺少界面键：$missingZh", missingZh.isEmpty())
        assertTrue("英文资源缺少界面键：$missingEn", missingEn.isEmpty())
    }
}

package top.tianyan.app.harness

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ask_user 参数解析与答案格式化：ToolExecutor 校验入参与 UI 问题卡共用同一解析口径。 */
class AskUserQuestionsTest {

    @Test fun `timeout selects explicit recommendation and never claims a human answered`() {
        val output = AskUserQuestions.timeoutOutput("""{"questions":[{"question":"方式？","options":[
            {"label":"A"},{"label":"B","recommended":true}]}]}""")
        assertTrue(output.contains("→ B"))
        assertTrue(output.contains("非用户确认"))
        assertTrue(output.contains("60 秒"))
    }

    @Test fun `timeout supports first option and free text default without inventing missing facts`() {
        val output = AskUserQuestions.timeoutOutput("""{"questions":[
            {"question":"方式？","options":[{"label":"A"},{"label":"B"}]},
            {"question":"版本？","default_answer":"Debug"},{"question":"缺失信息？"}]}""")
        assertTrue(output.contains("→ A"))
        assertTrue(output.contains("→ Debug"))
        assertTrue(output.contains("不得编造凭据"))
    }

    @Test
    fun `parses options with header and default allowCustom`() {
        val args = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("question", "选择构建方式？")
                        put("header", "构建")
                        putJsonArray("options") {
                            add(buildJsonObject { put("label", "Debug"); put("description", "快速验证") })
                            add(buildJsonObject { put("label", "Release") })
                        }
                    },
                )
            }
        }

        val questions = AskUserQuestions.parse(args)

        assertEquals(1, questions.size)
        assertEquals("选择构建方式？", questions[0].question)
        assertEquals("构建", questions[0].header)
        assertEquals(2, questions[0].options.size)
        assertEquals("快速验证", questions[0].options[0].description)
        assertEquals("Release", questions[0].options[1].label)
        assertNull(questions[0].options[1].description)
        assertTrue(questions[0].allowCustom)
    }

    @Test
    fun `free text question has no options and forces custom input`() {
        val args = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("question", "补充说明目标用户？") })
            }
        }

        val questions = AskUserQuestions.parse(args)

        assertEquals(0, questions[0].options.size)
        assertTrue(questions[0].allowCustom)
    }

    @Test
    fun `rejects empty question missing questions or duplicate labels`() {
        val emptyQuestion = buildJsonObject {
            putJsonArray("questions") { add(buildJsonObject { put("header", "x") }) }
        }
        val missingQuestions = buildJsonObject { }
        val duplicateLabels = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("question", "q")
                        putJsonArray("options") {
                            add(buildJsonObject { put("label", "A") })
                            add(buildJsonObject { put("label", "A") })
                        }
                    },
                )
            }
        }

        listOf(emptyQuestion to "question 不能为空", missingQuestions to "必须是问题对象数组", duplicateLabels to "重复选项")
            .forEach { (args, hint) ->
                val error = runCatching { AskUserQuestions.parse(args) }.exceptionOrNull()
                assertTrue("应拒绝：$hint", error is IllegalArgumentException && error.message!!.contains(hint))
            }
    }

    @Test
    fun `formatAnswers maps answers back to question text`() {
        val questionsJson = buildJsonObject {
            putJsonArray("questions") {
                add(
                    buildJsonObject {
                        put("question", "选择构建方式？")
                        putJsonArray("options") {
                            add(buildJsonObject { put("label", "Debug") })
                            add(buildJsonObject { put("label", "Release") })
                        }
                    },
                )
                add(buildJsonObject { put("question", "目标版本号？") })
            }
        }.toString()
        val answersJson = buildJsonArray {
            add(buildJsonObject { put("index", 1); put("answer", "2.0.1") })
            add(buildJsonObject { put("index", 0); put("answer", "Release") })
        }.toString()

        val output = AskUserQuestions.formatAnswers(questionsJson, answersJson)

        assertTrue(output.contains("1. 选择构建方式？"))
        assertTrue(output.contains("→ Release"))
        assertTrue(output.contains("2. 目标版本号？"))
        assertTrue(output.contains("→ 2.0.1"))
    }

    @Test
    fun `formatAnswers falls back to raw json when questions unparseable`() {
        val output = AskUserQuestions.formatAnswers("not-json", """[{"index":0,"answer":"ok"}]""")
        assertTrue(output.contains("用户已回答"))
        assertTrue(output.contains("ok"))
    }
}

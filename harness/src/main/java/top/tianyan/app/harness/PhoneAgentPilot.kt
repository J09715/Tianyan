package top.tianyan.app.harness

import java.util.Base64
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.model.workflow.PhoneWorkflowOperation
import top.tianyan.app.runtime.gui.GuiKey
import top.tianyan.app.runtime.gui.GuiPrimitive
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.tianyan.app.runtime.virtualdisplay.VirtualScreenToolkit
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskControl
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskState
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayHud

/**
 * 用设置里选定的手机操作模型看虚拟屏截图，并按它输出的动作去点、滑、输入。
 * 主模型只负责下达任务，不再自己一轮轮截图猜坐标。
 */
internal class PhoneAgentPilot(
    private val providerClient: ProviderClient,
    private val preferences: AgentPreferences,
    private val coordinator: VirtualDisplayCoordinator,
    private val toolkit: VirtualScreenToolkit,
    private val services: PhoneAgentServices? = null,
) {
    suspend fun run(session: String, goal: String, packageName: String, maxSteps: Int, workflowName: String = ""): Pair<Boolean, String> = coroutineScope {
        val task = coordinator.phoneTasks.start(session, currentCoroutineContext()[Job]!!)
            ?: return@coroutineScope false to "这个虚拟屏会话已有手机任务，请先停止它或使用其他 session。"
        try {
            services?.log(session, "RunStart executor=phone-agent maxSteps=$maxSteps record=${workflowName.isNotBlank()}")
            val result = runTask(session, goal, packageName, maxSteps.coerceIn(1, 20), task, workflowName)
            if (task.state.value != PhoneTaskState.NEEDS_USER && task.state.value != PhoneTaskState.CANCELLED) {
                task.finish(if (result.first) PhoneTaskState.COMPLETED else PhoneTaskState.FAILED)
                if (!result.first) VirtualDisplayHud.setStep("任务未完成 · 查看聊天中的原因")
            }
            services?.log(session, "RunEnd success=${result.first} state=${task.state.value}")
            result.first to "执行者：手机操作模型。\n${result.second}"
        } catch (e: CancellationException) {
            task.finish(PhoneTaskState.CANCELLED)
            VirtualDisplayHud.setStep("任务已停止")
            throw e
        } catch (e: Exception) {
            task.finish(PhoneTaskState.FAILED)
            services?.log(session, "RunError kind=${e::class.java.simpleName}")
            VirtualDisplayHud.setStep("任务执行失败")
            throw e
        } finally {
            coordinator.phoneTasks.release(session, task)
        }
    }

    private suspend fun runTask(
        session: String, goal: String, packageName: String, maxSteps: Int, task: PhoneTaskControl, workflowName: String,
    ): Pair<Boolean, String> {
        val endpoint = preferences.phoneAgentConfig.first()
        if (!endpoint.ready) {
            return false to "还没有配置手机操作模型。请到设置 → 智能体与模型 → 虚拟屏 → 手机操作模型，单独填写接口。这和聊天主模型不是同一个地方。智谱 Base URL 是 https://open.bigmodel.cn/api/paas/v4 ，模型名是 autoglm-phone。"
        }
        val model = phoneAgentModel(endpoint)
        services?.log(session, "ModelConfig model=${model.model} max_tokens=${model.maxTokens}")
        val recording = mutableListOf<PhoneWorkflowOperation>()
        var recordingClean = true
        var lastVersion = task.awaitReady()
        if (coordinator.getDisplayId(session) == null && coordinator.ensureVirtualDisplay(session) == null) {
            return false to "虚拟屏创建失败，手机操作模型无法开始。"
        }
        if (packageName.isNotBlank()) {
            val ready = task.awaitReady()
            if (ready != lastVersion) recordingClean = false
            lastVersion = ready
            val pkg = resolveLaunchablePackage(packageName, coordinator.installedApps())
                ?: return false to "找不到应用「$packageName」。请改用包名，或不要传 package，让手机操作模型自己打开。"
            if (!task.isCurrent(lastVersion)) return false to "启动应用前人工介入，任务已停止；请核对页面后重新运行。"
            if (!coordinator.launchApp(session, pkg)) return false to "虚拟屏启动应用失败：$pkg"
            recording += PhoneWorkflowOperation("virtual_screen_launch", mapOf("package" to pkg), 900)
            if (!task.isCurrent(lastVersion)) recordingClean = false
            delay(900)
        }
        val notes = ArrayList<String>(maxSteps)
        coordinator.reveal(session, "手机模型接手")?.let { notes += it }
        val progress = PhoneAgentProgress()
        var invalidReplies = 0
        var step = 0
        var request = 0
        while (step < maxSteps) {
            val version = task.awaitReady()
            if (version != lastVersion) recordingClean = false
            lastVersion = version
            val png = coordinator.requestScreenshot(session)
                ?: return false to finish(notes, "第 ${step + 1} 步截图失败，虚拟屏没有画面。")
            val size = screenSize(session, png)
                ?: return false to finish(notes, "第 ${step + 1} 步不知道虚拟屏尺寸，无法把相对坐标换成像素。")
            val history = if (notes.isEmpty()) "这是第一步。" else "已经做过：\n" + notes.joinToString("\n")
            val recordingHint = if (workflowName.isNotBlank() && recording.isEmpty() && packageName.isBlank())
                "\n需要录制可复用工作流，请先用 Launch 打开目标应用，再操作。" else ""
            if (!task.isCurrent(version)) { recordingClean = false; continue }
            val image = ImagePayloadCompressor.downscaleDataUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(png))
            val started = System.nanoTime()
            services?.log(session, "ModelRequest request=${++request} step=${step + 1} display=${coordinator.getDisplayId(session)} frame=${size.first}x${size.second} pngBytes=${png.size} imageChars=${image.length}")
            val reply = try {
                providerClient.chat(
                    model,
                    listOf(
                        ApiMessage(role = "system", content = phoneAgentSystemPrompt()),
                        ApiMessage(
                            role = "user",
                            content = "任务：$goal\n$history$recordingHint\n请根据截图输出下一个动作。",
                            imageUrls = listOf(image),
                        ),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                services?.log(session, "ModelError request=$request elapsedMs=${(System.nanoTime() - started) / 1_000_000} kind=${e::class.java.simpleName} outputBudgetRejected=${e.message?.contains("max_tokens", true) == true}")
                return false to finish(notes, "手机操作模型请求失败：${e.message}")
            }
            services?.log(session, "ModelResponse request=$request elapsedMs=${(System.nanoTime() - started) / 1_000_000} contentChars=${reply.content?.length ?: 0} reasoningChars=${reply.reasoningContent?.length ?: 0} inputTokens=${reply.usage.inputTokens} outputTokens=${reply.usage.outputTokens}")
            currentCoroutineContext().ensureActive()
            // 人工触摸或暂停/继续后，这次推理基于旧截图，必须重新观察。
            if (!task.isCurrent(version)) { recordingClean = false; continue }
            val text = reply.content.orEmpty()
            val action = parsePhoneAgentAction(text)
            if (action == null) {
                services?.log(session, "ActionParseFailed request=$request")
                invalidReplies++
                if (invalidReplies >= 2) return false to finish(notes, "连续两次无法解析正式动作，请检查手机模型协议。")
                notes += "格式错误：请仅在 <answer> 内输出一条完整 do(...) 或 finish(message=...)，使用定义的动作和参数。"
                continue
            }
            invalidReplies = 0
            val frame = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
            progress.beforeAction(action, frame)?.let { return false to finish(notes, it) }
            step++
            services?.log(session, "Action step=$step ${phoneActionLog(action)}")
            if (!task.isCurrent(version)) { recordingClean = false; continue }
            when (action) {
                is PhoneAgentAction.Finish -> {
                    task.finish(PhoneTaskState.COMPLETED)
                    coordinator.reveal(session, "任务完成")
                    val saved = when {
                        workflowName.isBlank() -> ""
                        !recordingClean -> "\n存在人工介入或失败步骤，未自动保存工作流；请在编辑器中手动整理。"
                        phoneRecordingIssue(recording) != null -> "\n${phoneRecordingIssue(recording)}"
                        services == null -> "\n工作流保存服务未连接，未保存。"
                        else -> try { "\n" + services.save(workflowName, recording) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { "\n操作结束，但保存工作流失败：${e.message}" }
                    }
                    return true to finish(notes, action.message + saved)
                }
                is PhoneAgentAction.TakeOver -> {
                    task.finish(PhoneTaskState.NEEDS_USER)
                    coordinator.reveal(session, "需要你接手")
                    return false to finish(notes, "需要人工接手：${action.message.ifBlank { "登录、验证码或支付" }}。请在悬浮窗上继续操作。")
                }
                is PhoneAgentAction.Note -> {
                    notes += "$step. 记录：${action.message.ifBlank { "当前页面" }}"
                    coordinator.reveal(session, "记录页面")
                }
                else -> {
                    val actionStarted = System.nanoTime()
                    val performed = perform(session, action, size, task, version)
                    services?.log(session, "Injection step=$step success=${performed.first} elapsedMs=${(System.nanoTime() - actionStarted) / 1_000_000}")
                    if (!task.isCurrent(version)) {
                        recordingClean = false
                        notes += "$step. ${actionLabel(action)} 期间人工介入，部分输入可能已发出；重新截图核对。"
                        continue
                    }
                    notes += "$step. ${actionLabel(action)}：${if (performed.first) "输入已发出/等待截图核验" else "执行失败"}；${performed.second}"
                    coordinator.reveal(session, performed.second.take(24))
                    progress.afterAction(performed.first)?.let { return false to finish(notes, it) }
                    val waitMs = when (action) {
                        is PhoneAgentAction.Wait -> action.durationMs
                        is PhoneAgentAction.Launch -> 900
                        else -> 700
                    }
                    if (performed.first) action.workflowOperation(waitMs, performed.second.removePrefix("启动 "))?.let { recording += it }
                    else recordingClean = false
                    delay(waitMs)
                }
            }
        }
        return false to finish(notes, "已达到 $maxSteps 步，任务还没有完成。可以把下一步再交给手机操作模型。")
    }

    private fun screenSize(session: String, png: ByteArray): Pair<Int, Int>? {
        val (width, height) = VirtualScreenHostActions.pngDimensions(png)
        if (width > 1 && height > 1) return width to height
        return coordinator.getVideoSize(session)?.takeIf { it.first > 1 && it.second > 1 }
    }

    private suspend fun perform(
        session: String, action: PhoneAgentAction, size: Pair<Int, Int>, task: PhoneTaskControl, version: Long,
    ): Pair<Boolean, String> {
        val canInject = { task.isCurrent(version) }
        suspend fun execute(primitive: GuiPrimitive) = toolkit.execute(session, primitive, canInject)
        val result = when (action) {
            is PhoneAgentAction.Tap -> execute(GuiPrimitive.Tap(phoneAgentPoint(action.x, size.first), phoneAgentPoint(action.y, size.second)))
            is PhoneAgentAction.DoubleTap -> execute(
                GuiPrimitive.DoubleTap(phoneAgentPoint(action.x, size.first), phoneAgentPoint(action.y, size.second)),
            )
            is PhoneAgentAction.LongPress -> execute(
                GuiPrimitive.LongPress(phoneAgentPoint(action.x, size.first), phoneAgentPoint(action.y, size.second)),
            )
            is PhoneAgentAction.Swipe -> execute(
                GuiPrimitive.Swipe(
                    phoneAgentPoint(action.x1, size.first),
                    phoneAgentPoint(action.y1, size.second),
                    phoneAgentPoint(action.x2, size.first),
                    phoneAgentPoint(action.y2, size.second),
                ),
            )
            is PhoneAgentAction.Type -> toolkit.setText(session, action.text, canInject)
            is PhoneAgentAction.Launch -> {
                val requested = action.app.trim()
                val pkg = resolveLaunchablePackage(requested, coordinator.installedApps())
                    ?: return false to "找不到应用「$requested」。请点击桌面上的图标，或改用包名。"
                if (!canInject()) return false to "应用启动已中止：任务暂停或画面失效"
                return if (coordinator.launchApp(session, pkg)) true to "启动 $pkg" else false to "启动失败 $pkg"
            }
            PhoneAgentAction.Back -> execute(GuiPrimitive.Key(GuiKey.BACK))
            PhoneAgentAction.Home -> execute(GuiPrimitive.Key(GuiKey.HOME))
            is PhoneAgentAction.Wait -> return true to "等待页面 ${action.durationMs}ms"
            is PhoneAgentAction.Note -> return true to "记录：${action.message}"
            is PhoneAgentAction.Finish, is PhoneAgentAction.TakeOver -> return false to "这个动作不能注入"
        }
        return result.success to result.message
    }

    private fun finish(notes: List<String>, outcome: String): String = buildString {
        append(outcome)
        if (notes.isNotEmpty()) {
            append("\n操作记录：\n")
            append(notes.joinToString("\n"))
        }
    }

}

private fun actionLabel(action: PhoneAgentAction): String = when (action) {
    is PhoneAgentAction.Type -> "Type(${action.text.length} 字)"
    else -> action.toString()
}

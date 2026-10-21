// language: Kotlin, module: feature/components, ui: Compose Material 3
package top.tianyan.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R
import top.tianyan.app.ui.theme.Motion
import top.tianyan.app.ui.theme.Spacing

/**
 * 「出错」状态（Error State）单一入口。
 *
 * ## 这个文件解决什么
 *
 * 引入前各屏的失败分支有三种写法：一是把 `Throwable.message` 直接 `Text()` 出来
 * （用户看到 `java.io.IOException: broken pipe`）；二是只弹一个 toast，转瞬即忘；
 * 三是干脆吞掉，界面停在空列表上，用户以为「就是没有数据」。
 *
 * 三种都错在同一个地方：**没区分「用户要做什么」和「开发者要看什么」**。
 * 前者是错误标题 + 出路，后者是折叠起来的技术细节 + 复制按钮。这个文件把两者分层，
 * 默认只给前者。
 *
 * ## 分类而不是透传
 *
 * [classifyError] 把异常翻译成人话。翻译表覆盖本仓实际会抛出的绝大多数类型：
 * PRoot/RootFS 解压失败、PTY 启动失败、网络与模型 API 失败、权限被拒、磁盘满。
 * 未知类型一律落到「未知错误」并保留原始信息在详情里——**不要**为了好看而丢掉它，
 * 否则线上问题无法定位。
 *
 * ## 出路优先级
 *
 * 可重试的错（网络抖动、超时、临时 IO）给「重试」；不可重试的（权限、配置缺失、
 * 空间不足）给「去设置」，把用户送到能修的地方而不是让他反复点重试。
 */

/** 错误类别。用于选图标、选语气、决定给「重试」还是「去修」。 */
enum class ErrorKind {
    /** 网络不可达、DNS、连接中断、模型 API 5xx。可重试。 */
    Network,

    /** 请求超时。可重试，但文案要提示「可能是网络慢」。 */
    Timeout,

    /** 无权限：存储权限、SU/PRoot、目录不可写。重试无用，要去授权。 */
    Permission,

    /** 磁盘空间不足。重试无用。 */
    Storage,

    /** 沙箱/运行时未就绪：RootFS 未安装、PRoot 缺失、PTY 启动失败。 */
    Runtime,

    /** 认证失败：API Key 无效、登录过期。 */
    Auth,

    /** 内容或参数不合法：模型返回无法解析、文件格式错。 */
    Invalid,

    /** 其余一切。保留原始信息在详情里。 */
    Unknown,
}

/**
 * 错误类别的展示属性：图标 + 语气 + 默认标题。
 *
 * 标题写成「发生了什么」而不是「失败了」——后者对用户零信息量。
 */
private data class ErrorPresentation(
    val icon: RuntimeIconName,
    val tone: StateTone,
    val title: String,
    val retryable: Boolean,
)

private fun ErrorKind.presentation(): ErrorPresentation = when (this) {
    ErrorKind.Network -> ErrorPresentation(
        icon = RuntimeIconName.Network,
        tone = StateTone.Danger,
        title = "网络连接失败",
        retryable = true,
    )
    ErrorKind.Timeout -> ErrorPresentation(
        icon = RuntimeIconName.Activity,
        tone = StateTone.Warning,
        title = "请求超时",
        retryable = true,
    )
    ErrorKind.Permission -> ErrorPresentation(
        icon = RuntimeIconName.Shield,
        tone = StateTone.Warning,
        title = "缺少必要权限",
        retryable = false,
    )
    ErrorKind.Storage -> ErrorPresentation(
        icon = RuntimeIconName.Storage,
        tone = StateTone.Warning,
        title = "存储空间不足",
        retryable = false,
    )
    ErrorKind.Runtime -> ErrorPresentation(
        icon = RuntimeIconName.Terminal,
        tone = StateTone.Danger,
        title = "运行环境未就绪",
        retryable = true,
    )
    ErrorKind.Auth -> ErrorPresentation(
        icon = RuntimeIconName.Key,
        tone = StateTone.Warning,
        title = "认证失败",
        retryable = false,
    )
    ErrorKind.Invalid -> ErrorPresentation(
        icon = RuntimeIconName.Alert,
        tone = StateTone.Warning,
        title = "数据无法解析",
        retryable = true,
    )
    ErrorKind.Unknown -> ErrorPresentation(
        icon = RuntimeIconName.Alert,
        tone = StateTone.Danger,
        title = "出现未知错误",
        retryable = true,
    )
}

/**
 * 该类错误是否值得给「重试」按钮。
 *
 * 权限、认证、空间不足三类重试必然还是失败，反复点只是消耗用户的耐心与电量，
 * 所以它们引导去「去设置」而不是「重试」。
 */
fun ErrorKind.isRetryable(): Boolean = when (this) {
    ErrorKind.Permission, ErrorKind.Auth, ErrorKind.Storage -> false
    else -> true
}

/** 该类错误的默认标题。 */
fun ErrorKind.presentationTitle(): String = presentation().title

/**
 * 把异常归类。
 *
 * 判据用**类名与 message 的弱匹配**而不是 `is IOException` 之类：本仓的运行时错误
 * 大量来自 subprocess / PTY / 解压器，它们最终都被包成 `RuntimeException`，类型信息
 * 在包装时就丢了。所以按 message 关键字兜底比按类型准。
 *
 * 顺序有讲究：`Permission` 要在 `Network` 之前判——`EPERM` 的 message 里也可能出现
 * 「socket」，先判网络会归错。
 */
fun classifyError(error: Throwable?): ErrorKind {
    if (error == null) return ErrorKind.Unknown
    val text = buildString {
        append(error::class.java.simpleName)
        append(' ')
        append(error.message.orEmpty())
    }.lowercase()

    // 权限先判：EPERM/EACCES 常伴随网络与文件字样，判早于其它类
    if (listOf("permission", "eacces", "eperm", "denied", "not granted", "无权限", "拒绝访问")
            .any { it in text }
    ) {
        return ErrorKind.Permission
    }
    if (listOf("no space", "enospc", "disk full", "空间不足", "存储空间").any { it in text }) {
        return ErrorKind.Storage
    }
    if (listOf("timeout", "timed out", "超时").any { it in text }) {
        return ErrorKind.Timeout
    }
    if (listOf("unauthorized", "401", "403", "api key", "invalid key", "认证", "密钥")
            .any { it in text }
    ) {
        return ErrorKind.Auth
    }
    if (listOf("unknownhost", "unresolvedaddress", "connect", "socket", "network",
            "connection reset", "eof", "ssl", "网络", "连接")
            .any { it in text }
    ) {
        return ErrorKind.Network
    }
    if (listOf("proot", "rootfs", "pty", "termux", "sandbox", "exited with",
            "沙箱", "解压")
            .any { it in text }
    ) {
        return ErrorKind.Runtime
    }
    if (listOf("json", "parse", "malformed", "unexpected token", "serial", "解析")
            .any { it in text }
    ) {
        return ErrorKind.Invalid
    }
    return ErrorKind.Unknown
}

/**
 * 整屏错误态。
 *
 * @param error 原始异常。传 null 时按 [ErrorKind.Unknown] 处理——有些调用点只有
 *   一个布尔量的「失败了」，没有异常对象，那也不该被拒之门外。
 * @param technicalDetail 额外技术信息（命令行、路径、状态码）。会与异常的
 *   `toString()` 一起放进可展开的详情区。
 * @param onRetry 传 null 表示这个错不该重试（例如权限缺失），此时即便 [ErrorKind]
 *   标称可重试也不会显示重试按钮。
 * @param onOpenSettings 不可重试错误的「去修」入口。
 */
@Composable
fun RuntimeErrorState(
    error: Throwable?,
    modifier: Modifier = Modifier,
    kind: ErrorKind = classifyError(error),
    title: String = kind.presentation().title,
    description: String? = null,
    technicalDetail: String? = null,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "",
    onOpenSettings: (() -> Unit)? = null,
    settingsLabel: String = "",
) {
    val presentation = kind.presentation()
    val accent = presentation.tone.color()
    val rawDetail = remember(error, technicalDetail) {
        technicalDetail ?: error?.toString()
    }
    var detailExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconTile(icon = presentation.icon, color = accent, size = 56.dp)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description ?: defaultErrorDescription(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // 不可重试的错误（权限、认证、空间不足）即使调用方传了 onRetry 也不给重试按钮——
            // 让用户反复点一个必然失败的动作是更差的体验，此时应该给「去设置」。
            val showRetry = onRetry != null && kind.isRetryable()
            if (showRetry || onOpenSettings != null) {
                Row(
                    modifier = Modifier.padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (showRetry) {
                        RuntimeButton(onClick = onRetry!!, tonal = onOpenSettings != null) {
                            Text(
                                text = retryLabel.ifEmpty {
                                    stringResource(R.string.components_retry)
                                },
                            )
                        }
                    }
                    if (onOpenSettings != null) {
                        RuntimeButton(onClick = onOpenSettings) {
                            Text(
                                text = settingsLabel.ifEmpty {
                                    stringResource(R.string.components_goto_settings)
                                },
                            )
                        }
                    }
                }
            }

            if (rawDetail != null) {
                RuntimeTextButton(
                    onClick = { detailExpanded = !detailExpanded },
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        text = if (detailExpanded) {
                            stringResource(R.string.components_hide_technical_detail)
                        } else {
                            stringResource(R.string.components_show_technical_detail)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = detailExpanded,
                    enter = expandVertically(animationSpec = Motion.medium()),
                    exit = shrinkVertically(animationSpec = Motion.fast()),
                ) {
                    ErrorDetailBox(detail = rawDetail)
                }
            }
        }
    }
}

/**
 * 行内错误态：横排一条，塞进列表或卡片内。
 *
 * 用在「某一块数据加载失败但页面其它部分正常」的场景。整页都失败就用
 * [RuntimeErrorState]，别用这个硬撑——单行放不下出路说明。
 */
@Composable
fun RuntimeErrorStateInline(
    error: Throwable?,
    modifier: Modifier = Modifier,
    kind: ErrorKind = classifyError(error),
    title: String = kind.presentation().title,
    onRetry: (() -> Unit)? = null,
) {
    val accent = kind.presentation().tone.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon = kind.presentation().icon, color = accent, size = 36.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = error?.message?.take(120) ?: defaultErrorDescription(kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRetry != null) {
            RuntimeTextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.components_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
        }
    }
}

/** 详情区：等宽字体、可滚动、可全选复制。 */
@Composable
private fun ErrorDetailBox(detail: String) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 每类错误的默认描述：说「怎么办」，不说「错了」。 */
private fun defaultErrorDescription(kind: ErrorKind): String = when (kind) {
    ErrorKind.Network -> "检查网络连接后重试。若使用代理，确认代理仍在运行。"
    ErrorKind.Timeout -> "网络较慢或服务端响应超时，稍后重试通常可以恢复。"
    ErrorKind.Permission -> "天衍缺少完成该操作所需的权限，请到系统设置中授予。"
    ErrorKind.Storage -> "清理一些空间后重试。沙箱镜像与工作区会占用较多存储。"
    ErrorKind.Runtime -> "Linux 运行环境尚未就绪（沙箱未安装或进程启动失败）。"
    ErrorKind.Auth -> "API Key 或登录状态已失效，请在设置中重新填写。"
    ErrorKind.Invalid -> "返回的数据格式与预期不符，可能是服务端版本变更。"
    ErrorKind.Unknown -> "操作未能完成。展开技术详情可以获得更多线索。"
}

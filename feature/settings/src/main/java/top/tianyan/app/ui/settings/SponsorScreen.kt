package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.settings.LocalizedText as Text
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeSettingsScaffold
import top.tianyan.app.feature.settings.R
// 赞赏码与赞助邮箱
private const val SPONSOR_EMAIL = "wangkebin1997@gmail.com"
// 收款码已打包进 settings 模块 mipmap 资源（settings_qr_*），离线可用、不依赖外网
private val SPONSOR_ALIPAY_QR_RES = R.mipmap.settings_qr_alipay
private val SPONSOR_WECHAT_QR_RES = R.mipmap.settings_qr_wechat

/**
 * 二级子页 5：赞助支持（鸣谢名单来自 GitHub 上的 sponsors.json，进入页面时才拉取）
 */
@Composable
fun SponsorScreen(
    onBack: () -> Unit,
    viewModel: SponsorListViewModel? = previewOrNull { koinViewModel() },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sponsorState by viewModel?.state?.collectAsStateWithLifecycle() ?: previewStateOf(SponsorListUiState.Loading)
    var showAlipayQrDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showWeChatQrDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    if (showAlipayQrDialog) {
        SponsorQrDialog(
            title = "支付宝赞赏码",
            qrRes = SPONSOR_ALIPAY_QR_RES,
            onDismiss = { showAlipayQrDialog = false },
        )
    }
    if (showWeChatQrDialog) {
        SponsorQrDialog(
            title = "微信赞赏码",
            qrRes = SPONSOR_WECHAT_QR_RES,
            onDismiss = { showWeChatQrDialog = false },
        )
    }

    RuntimeSettingsScaffold(
        title = "赞助支持", onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel?.refresh() }, contentDescription = "刷新名单") {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(20.dp))
            }
        },
    ) {
        // 顶部引言
        item {
            SettingsDetailCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.tertiaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            RuntimeIcon(RuntimeIconName.Sponsor, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Text(
                            "感谢你考虑赞助天衍",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "天衍（Tianyan）完全免费且开源，由作者在业余时间独立维护。你的每一份赞助都将用于购买 API Token 与持续的开发迭代，帮助我们把项目做得更好。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 赞助方式
        item {
            Text(
                text = "赞助方式",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            SettingsGroup {
                SettingsRow(
                    icon = RuntimeIconName.Sponsor,
                    title = "支付宝赞赏码",
                    subtitle = "支付宝扫一扫，随心赞赏",
                    onClick = { showAlipayQrDialog = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Sponsor,
                    title = "微信赞赏码",
                    subtitle = "微信扫一扫，随心赞赏",
                    onClick = { showWeChatQrDialog = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsRow(
                    icon = RuntimeIconName.Mail,
                    title = "Token 赞助",
                    subtitle = "邮箱: wangkebin1997@gmail.com · 发邮件即可",
                    onClick = { sendSponsorEmail(context) },
                )
            }
        }

        // 回馈与鸣谢
        item {
            Text(
                text = "回馈与鸣谢",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            SettingsGroup {
                when (val s = sponsorState) {
                    SponsorListUiState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "正在加载鸣谢名单…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is SponsorListUiState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "名单加载失败：${s.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { viewModel?.refresh() }) {
                                Text("重试")
                            }
                        }
                    }
                    is SponsorListUiState.Success -> {
                        SponsorKindGroup("资金赞助", s.entries.filter { it.kind == SponsorKind.Funding })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SponsorKindGroup("资源赞助", s.entries.filter { it.kind == SponsorKind.Resource })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SponsorKindGroup("贡献者", s.entries.filter { it.kind == SponsorKind.Contribution })
                    }
                }
            }
        }
    }
}

/** 将打包在 mipmap 中的赞赏码二维码保存至系统相册 Pictures/Tianyan */
private fun saveSponsorQrToGallery(context: Context, qrRes: Int, namePrefix: String): Boolean {
    val resolver = context.contentResolver
    val filename = "tianyan-sponsor-$namePrefix-${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Tianyan")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        val bitmap = BitmapFactory.decodeResource(context.resources, qrRes)
            ?: error("无法解码赞赏码资源")
        resolver.openOutputStream(target)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: error("无法写入目标文件")
        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        true
    }.getOrElse {
        resolver.delete(target, null, null)
        false
    }
}

/** 赞赏码弹窗：展示打包在 mipmap 中的二维码图片 */
@Composable
private fun SponsorQrDialog(
    title: String,
    qrRes: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Sponsor, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    SubcomposeAsyncImage(
                        model = qrRes,
                        contentDescription = "$title 图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        error = {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Sponsor, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "二维码加载失败，请稍后重试",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                Text(
                    "打开对应 App 扫一扫即可赞赏，感谢支持",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val prefix = if (title.contains("微信")) "wechat" else "alipay"
                    val success = saveSponsorQrToGallery(context, qrRes, prefix)
                    if (success) {
                        Toast.makeText(context, "二维码已保存至相册", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Download, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Text("保存到相册", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

/** 唤起邮件客户端撰写 Token 赞助邮件（正文预填模板）；无邮件客户端时复制邮箱兜底 */
private fun sendSponsorEmail(context: Context) {
    val body = buildString {
        appendLine("你好，我想赞助天衍一个 Token，支持你继续开发：")
        appendLine()
        appendLine("API Base URL：")
        appendLine("API Key：")
        appendLine("模型名称（可选）：")
        appendLine()
        appendLine("感谢你的付出！")
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SPONSOR_EMAIL")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "天衍 Token 赞助")
        putExtra(Intent.EXTRA_TEXT, body)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("天衍赞助邮箱", SPONSOR_EMAIL))
        Toast.makeText(context, "已复制赞助邮箱：$SPONSOR_EMAIL，请打开邮箱写信", Toast.LENGTH_LONG).show()
    }
}

/** 鸣谢分类块：标题 + 头像名单（FlowRow 胶囊），空列表时显示占位文案 */
@Composable
private fun SponsorKindGroup(
    title: String,
    entries: List<SponsorEntry>,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text(
                text = "暂无，感谢每一位默默支持的朋友",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { entry -> SponsorChip(entry) }
            }
        }
    }
}

/** 单个鸣谢胶囊：头像 + 名称（可选补充说明） */
@Composable
private fun SponsorChip(entry: SponsorEntry) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = entry.avatarUrl,
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            RuntimeIcon(
                                RuntimeIconName.Sponsor,
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.note != null) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

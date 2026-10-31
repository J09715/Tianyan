package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.HorizontalDivider
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import top.tianyan.app.ui.components.RuntimeSlider as Slider
import androidx.compose.material3.Surface
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import top.tianyan.app.ui.settings.LocalizedText as Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.model.AgentPlugin
import top.tianyan.app.harness.ContextWindowPolicy
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.core.model.AgentSubagent
import top.tianyan.app.core.model.AgentDepartments
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.SectionHeader
import kotlinx.coroutines.delay
import top.tianyan.app.core.model.skill.ClawHubMarketItem
import top.tianyan.app.ui.settings.skill.SkillSecurityAuditDialog

@Composable
internal fun BaseCommandTimeoutSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderMinutes by remember(currentValue) {
        mutableFloatStateOf((currentValue / 60f).coerceIn(1f, 60f))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDensity.rowHorizontalPadding, vertical = SettingsDensity.rowVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("base 命令默认超时", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderMinutes.toInt()} 分钟",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "适用于普通前台命令；模型可为单次命令指定 1–60 分钟，常驻任务应使用 process 工具",
            style = settingsSupportingStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderMinutes,
            onValueChange = { sliderMinutes = it },
            onValueChangeFinished = { onValueChange(sliderMinutes.toInt() * 60) },
            valueRange = 1f..60f,
            steps = 58,
        )
    }
}

@Composable
internal fun RoundsSliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("单回合最大工具轮次上限", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} 轮",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "防止复杂任务中模型陷入死循环；达到轮次后进入下方的自动续跑检查点",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 10f..300f,
            steps = 28, // 10, 20, 30 ... 300
        )
    }
}

/**
 * 全局并发 Agent 回合数。
 *
 * 为什么值得暴露给用户：这个值直接决定群聊里能同时开几个成员。
 * 默认 2 是保守值（移动端内存/网络/上游 API 限流都吃紧），
 * 但多成员群聊场景下用户需要主动调大，否则会看到「只有两个在干活」。
 */
@Composable
internal fun ConcurrencySliderRow(
    currentValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("同时执行的智能体数量", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "${sliderVal.toInt()} 个",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "群聊里多个成员同时回复时，超出这个数量的会排队等待；调大更快，但更吃内存与上游限流",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderVal,
            onValueChange = { sliderVal = it },
            onValueChangeFinished = { onValueChange(sliderVal.toInt()) },
            valueRange = 1f..10f,
            steps = 8, // 1..10 的整数档位（1,2,...,10）
        )
    }
}

@Composable
internal fun AgentSettingsGroup(content: @Composable () -> Unit) = SettingsGroup(content)

@Composable
internal fun AgentBlockTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun AgentToggleRow(
    icon: RuntimeIconName, title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) = ToggleRow(icon, title, subtitle, checked, onCheckedChange)

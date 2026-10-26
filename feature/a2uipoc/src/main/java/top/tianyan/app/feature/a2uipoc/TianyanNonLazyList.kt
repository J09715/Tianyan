package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 宿主自实现的 A2UI Basic Catalog「List」组件。
 *
 * 背景（为什么必须覆写）：
 * 官方 [androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1List] 在
 * direction=Vertical 时使用 LazyColumn、Horizontal 时使用 LazyRow。而 render_surface
 * 生成的界面是「内嵌在聊天流的 LazyColumn 项里」渲染的，聊天项在纵向上拿到的约束是
 * maxHeight = Infinity。垂直懒列表一旦测得无限高约束，foundation 会直接断言失败：
 *   androidx.compose.foundation -> checkScrollableContainerConstraints()
 *   IllegalStateException("Vertically scrollable component was measured with an
 *   infinity maximum height constraints ...")
 * 异常发生在 measure 阶段且无捕获点，进程被杀（用户表现为「滑到那张卡就闪退」）。
 *
 * 短列表用 Column/LazyRow 按内容撑开。纵向项数超过 [MAX_EAGER_CHILDREN] 时改用
 * 限高的 LazyColumn：父级给出的是有限高度，不会再触发那条无限高断言，超长列表在卡片内滚动。
 *
 * 接入方式（TianyanA2uiRenderer.kt）：
 *   private val catalog = materialA2uiBasicCatalogV1(
 *       image = TianyanImageComponent(),
 *       video = TianyanVideoComponent(),
 *       audioPlayer = TianyanAudioPlayerComponent(),
 *       urlOpener = TianyanUrlOpener,
 *       list = TianyanNonLazyList,          // <<< 新增这一行
 *   )
 */
internal object TianyanNonLazyList : A2uiBasicCatalogV1.List {

    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>,
        direction: A2uiBasicCatalogV1.List.Direction,
        align: A2uiBasicCatalogV1.List.Align,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val listModifier = modifier.listAccessibility(accessibility)
        when (direction) {
            A2uiBasicCatalogV1.List.Direction.Horizontal -> {
                LazyRow(
                    modifier = listModifier,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = align.toVerticalAlignment(),
                ) {
                    items(children, key = { it.id to it.baseDataPath }) { childRef ->
                        ListItem(childRef = childRef, modifier = Modifier)
                    }
                }
            }

            A2uiBasicCatalogV1.List.Direction.Vertical -> {
                if (children.size <= MAX_EAGER_CHILDREN) {
                    Column(
                        modifier = listModifier,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = align.toHorizontalAlignment(),
                    ) {
                        children.forEach { childRef ->
                            ListItem(childRef = childRef, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = listModifier.heightIn(max = MAX_LAZY_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = align.toHorizontalAlignment(),
                    ) {
                        items(children, key = { it.id to it.baseDataPath }) { childRef ->
                            ListItem(childRef = childRef, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun A2uiComponentScope.ListItem(
        childRef: A2uiComponentReference,
        modifier: Modifier = Modifier,
    ) {
        // 与官方 ListItemStateWrapper 等价：按子组件状态 加载中/失败/成功 三态渲染，
        // 去掉 AnimatedContent 过渡，避免依赖 MaterialA2uiDefaults 的内部成员。
        when (val state = observeA2uiComponentState(childRef)) {
            is A2uiComponentState.Success ->
                A2uiComponent(component = state.component, modifier = modifier)

            is A2uiComponentState.Error ->
                Text(
                    text = "子组件渲染失败：" + (state.exception.message ?: "未知错误"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = modifier,
                )

            is A2uiComponentState.Loading ->
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = modifier,
                )
        }
    }
}

private fun Modifier.listAccessibility(
    attributes: A2uiBasicCatalogV1.AccessibilityAttributes?,
): Modifier {
    val label = attributes?.label?.takeUnless { it.isBlank() }
    val description = attributes?.description?.takeUnless { it.isBlank() }
    val text = when {
        label != null && description != null -> "$label - $description"
        label != null -> label
        description != null -> description
        else -> return this
    }
    return semantics { contentDescription = text }
}

private fun A2uiBasicCatalogV1.List.Align.toHorizontalAlignment(): Alignment.Horizontal =
    when (this) {
        A2uiBasicCatalogV1.List.Align.Start -> Alignment.Start
        A2uiBasicCatalogV1.List.Align.Center -> Alignment.CenterHorizontally
        A2uiBasicCatalogV1.List.Align.End -> Alignment.End
        A2uiBasicCatalogV1.List.Align.Stretch -> Alignment.Start
    }

private fun A2uiBasicCatalogV1.List.Align.toVerticalAlignment(): Alignment.Vertical =
    when (this) {
        A2uiBasicCatalogV1.List.Align.Start -> Alignment.Top
        A2uiBasicCatalogV1.List.Align.Center -> Alignment.CenterVertically
        A2uiBasicCatalogV1.List.Align.End -> Alignment.Bottom
        A2uiBasicCatalogV1.List.Align.Stretch -> Alignment.Top
    }

private const val MAX_EAGER_CHILDREN = 12
private val MAX_LAZY_HEIGHT = 320.dp

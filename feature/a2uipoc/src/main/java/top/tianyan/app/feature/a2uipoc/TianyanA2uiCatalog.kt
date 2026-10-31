package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.harness.A2uiSurfaceBus
import top.tianyan.app.harness.A2uiSurfaceContract

/**
 * 天衍目录 = 官方 Basic Catalog 的全部组件，再加 [TianyanTableComponent]。
 * catalogId 用天衍自己的 URI；模型若仍写官方 basic catalog id，渲染前改写成这个 id。
 */
internal object TianyanA2uiCatalog {

    fun extend(basic: A2uiCatalog): A2uiCatalog = A2uiCatalog(
        catalogId = A2uiSurfaceContract.CATALOG_ID,
        components = basic.components + TianyanTableComponent,
        functions = basic.functions,
        themeSchema = basic.themeSchema,
    )

    fun alignCatalogId(messagesJson: String): String =
        messagesJson.replace(A2uiSurfaceContract.LEGACY_CATALOG_ID, A2uiSurfaceContract.CATALOG_ID)

    /** 协议里的 surfaceId 按会话加前缀，避免两个会话用同一个 id 时共用一张引擎界面。 */
    fun scopeMessages(messagesJson: String, sessionId: String): String {
        if (sessionId.isBlank()) return messagesJson
        val array = runCatching { Json.parseToJsonElement(messagesJson).jsonArray }.getOrNull() ?: return messagesJson
        return JsonArray(array.map { element ->
            val message = element as? JsonObject ?: return@map element
            JsonObject(message.mapValues { (_, value) -> scopeSurfaceField(value, sessionId) })
        }).toString()
    }

    private fun scopeSurfaceField(value: JsonElement, sessionId: String): JsonElement {
        val obj = value as? JsonObject ?: return value
        val surfaceId = obj["surfaceId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return value
        val copy = obj.toMutableMap()
        copy["surfaceId"] = JsonPrimitive(A2uiSurfaceBus.engineSurfaceId(sessionId, surfaceId))
        return JsonObject(copy)
    }
}

/**
 * 只读表格。聊天流里不用懒列表，避免和纵向消息列表嵌套滚动。
 * 列数和行数在 schema 里封顶，超宽时横向滚动。
 */
internal object TianyanTableComponent : A2uiComponent {

    private val columnsProperty = A2uiProperty.stringList(
        key = "columns",
        required = true,
        description = "表头，从左到右。",
        minItems = 1,
        maxItems = MAX_COLUMNS,
    )

    private val cellsProperty = A2uiProperty.stringList(
        key = "cells",
        required = true,
        description = "该行单元格，顺序和数量与 columns 一致。",
        minItems = 1,
        maxItems = MAX_COLUMNS,
    )

    private val rowsProperty = A2uiProperty.nestedList(
        key = "rows",
        properties = listOf(cellsProperty),
        required = true,
        description = "数据行。每项是 {cells: [字符串]}。",
        minItems = 1,
        maxItems = MAX_ROWS,
        isAdditionalPropertiesAllowed = false,
    )

    override val name: String = "Table"

    override val description: String =
        "只读表格，用于对比、排行和看板。columns 是表头，rows[].cells 与 columns 等长。"

    override val properties = listOf(columnsProperty, rowsProperty)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val headers = properties[columnsProperty].orEmpty().take(MAX_COLUMNS)
        val body = properties[rowsProperty].orEmpty().take(MAX_ROWS)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier,
        ) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
                HeaderRow(headers)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                body.forEach { row ->
                    val cells = row[cellsProperty].orEmpty()
                    DataRow(headers.indices.map { index -> cells.getOrElse(index) { "" } })
                }
            }
        }
    }

    @Composable
    private fun HeaderRow(headers: List<String>) {
        Row {
            headers.forEach { header ->
                Text(
                    header,
                    modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun DataRow(cells: List<String>) {
        Row {
            cells.forEach { cell ->
                Text(
                    cell,
                    modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    private const val MAX_COLUMNS = 6
    private const val MAX_ROWS = 12
}

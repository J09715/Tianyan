package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 赞助者/贡献者名单仓库。
 *
 * 名单外置在 GitHub 仓库根目录的 sponsors.json 中，App 只在赞助页面被打开时才请求一次，
 * 因此新增赞助者/贡献者只需修改 JSON 并推送，无需重新发布版本。
 *
 * sponsors.json 格式（kind/source 均与下方枚举的 @SerialName 对应）：
 * {
 *   "entries": [
 *     { "kind": "funding",      "name": "阿伟",   "source": "qq",    "identifier": "10001" },
 *     { "kind": "resource",     "name": "wkbin",  "source": "github", "identifier": "wkbin", "note": "提供推理 token" },
 *     { "kind": "contribution", "name": "路人甲", "source": "github", "identifier": "lurenjia", "avatar": "https://avatars.githubusercontent.com/u/1?s=96", "note": "上报多窗口闪退" }
 *   ]
 * }
 */
@Serializable
enum class SponsorSource {
    @SerialName("github") GitHub,
    @SerialName("qq") Qq,
}

/** 贡献类型：资金赞助 / 资源赞助(如提供 token、测试设备) / 贡献(提 bug、PR、建议) */
@Serializable
enum class SponsorKind {
    @SerialName("funding") Funding,
    @SerialName("resource") Resource,
    @SerialName("contribution") Contribution,
}

/** 鸣谢名单条目：name 为展示昵称，identifier 为 GitHub 用户名或 QQ 号，avatar 为可选头像地址，note 为补充说明 */
@Serializable
data class SponsorEntry(
    val kind: SponsorKind,
    val name: String,
    val source: SponsorSource,
    val identifier: String,
    val note: String? = null,
    val avatar: String? = null,
) {
    /** 优先使用名单里的头像；未填写时按来源生成 GitHub / QQ 公开头像 */
    val avatarUrl: String
        get() = avatar?.takeIf { it.isNotBlank() } ?: when (source) {
            SponsorSource.GitHub -> "https://avatars.githubusercontent.com/$identifier?s=96"
            SponsorSource.Qq -> "https://q1.qlogo.cn/g?b=qq&nk=$identifier&s=96"
        }
}

@Serializable
data class SponsorListDto(val entries: List<SponsorEntry> = emptyList())

class SponsorListRepository(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 拉取 GitHub 上的赞助者名单。仅在赞助页面打开时调用。
     *
     * 依次尝试 [SPONSORS_JSON_URLS]：main 分支 → 当前发布分支。
     * 之所以要多个地址：仓库默认分支是 main，而实际发版分支另有其名，
     * 文件只存在于其中一个时，单地址会让赞助页永远显示「加载失败」。
     */
    suspend fun loadEntries(): Result<List<SponsorEntry>> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in SPONSORS_JSON_URLS) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Tianyan-App")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) throw IllegalStateException("名单内容为空")
                    json.decodeFromString(SponsorListDto.serializer(), body).entries
                }
            }
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("名单加载失败"))
    }

    companion object {
        private const val REPO = "J09715/Tianyan"

        /** 按顺序尝试；第一个可用即返回。 */
        val SPONSORS_JSON_URLS: List<String> = listOf(
            "https://raw.githubusercontent.com/$REPO/main/sponsors.json",
            "https://raw.githubusercontent.com/$REPO/merge/taixu-v1.5.0/sponsors.json",
        )

        /** 兼容旧引用：默认使用第一个地址。 */
        const val SPONSORS_JSON_URL = "https://raw.githubusercontent.com/J09715/Tianyan/main/sponsors.json"
    }
}

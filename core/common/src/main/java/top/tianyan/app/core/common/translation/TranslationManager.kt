package top.tianyan.app.core.common.translation

import android.content.Context
import java.util.Locale
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.logging.AppLogger
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import android.app.DownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex

sealed interface TranslationModelStatus {
    /** 正在检测本地模型文件状态 */
    data object Checking : TranslationModelStatus
    /** 模型未下载（需前往下载约 60MB 模型包） */
    data object NotDownloaded : TranslationModelStatus
    /** 正在下载语种模型，包含当前阶段与下载进度详情 */
    data class Downloading(
        val progress: Float? = null,
        val step: Int = 1,
        val totalSteps: Int = 2,
        val stepName: String = "离线语言模型包",
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) : TranslationModelStatus {
        val detailText: String
            get() {
                return if (downloadedBytes > 0L && totalBytes > 0L) {
                    val dlMb = String.format(Locale.US, "%.1f", downloadedBytes.toDouble() / (1024 * 1024))
                    val totMb = String.format(Locale.US, "%.1f", totalBytes.toDouble() / (1024 * 1024))
                    val pct = ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    "$dlMb MB / $totMb MB ($pct%)"
                } else if (progress != null) {
                    "${((progress) * 100).toInt().coerceIn(0, 100)}% (第 $step/$totalSteps 阶段)"
                } else {
                    "第 $step/$totalSteps 阶段 (连接中...)"
                }
            }
    }
    /** 模型已就绪，完全支持离线本地翻译 */
    data object Ready : TranslationModelStatus
    /** 下载或执行发生错误 */
    data class Error(val message: String) : TranslationModelStatus
}

/**
 * Android ML Kit 本地离线翻译管理服务。
 * 负责管理英语 <-> 简体中文离线翻译模型生命周期（检测、下载、删除）与本地推理。
 */
class TranslationManager(
    private val context: Context,
    private val appLogger: AppLogger,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<TranslationModelStatus>(TranslationModelStatus.Checking)
    val status: StateFlow<TranslationModelStatus> = _status.asStateFlow()

    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.CHINESE)
        .build()

    private val translator: Translator by lazy {
        Translation.getClient(translatorOptions)
    }

    private val modelManager = RemoteModelManager.getInstance()
    private val englishModel = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
    private val chineseModel = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()

    /** 内存翻译缓存，避免重复推演计算，最多保存 100 条思考片段 */
    private val translationCache = Collections.synchronizedMap(
        object : LinkedHashMap<Int, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
                return size > 100
            }
        }
    )

    init {
        refreshStatus()
    }

    /** 异步检测模型下载状态 */
    fun refreshStatus() {
        scope.launch {
            _status.value = TranslationModelStatus.Checking
            try {
                val enReady = modelManager.isModelDownloaded(englishModel).awaitResult()
                val zhReady = modelManager.isModelDownloaded(chineseModel).awaitResult()
                _status.value = if (enReady && zhReady) {
                    TranslationModelStatus.Ready
                } else {
                    TranslationModelStatus.NotDownloaded
                }
            } catch (e: Exception) {
                appLogger.w("TranslationManager: Check translation models failed", e)
                _status.value = TranslationModelStatus.NotDownloaded
            }
        }
    }

    private val downloadMutex = Mutex()

    /** 查询系统 DownloadManager 中当前活跃下载任务已下载字节与总字节数 */
    private fun queryDownloadProgress(): Pair<Long, Long>? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return try {
            val query = DownloadManager.Query()
            dm.query(query)?.use { cursor ->
                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                var latestBytes = 0L
                var latestTotal = 0L
                var found = false

                while (cursor.moveToNext()) {
                    val status = if (statusCol != -1) cursor.getInt(statusCol) else -1
                    if (status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_PAUSED) {
                        val bytes = if (bytesCol != -1) cursor.getLong(bytesCol) else 0L
                        val total = if (totalCol != -1) cursor.getLong(totalCol) else 0L
                        if (total > 0L) {
                            latestBytes = bytes
                            latestTotal = total
                            found = true
                        }
                    }
                }
                if (found && latestTotal > 0L) {
                    Pair(latestBytes, latestTotal)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 下载离线模型包（分阶段下载英语与中文，合计约 60MB，带进度反馈） */
    suspend fun downloadModel(requireWifi: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) {
            return@withContext Result.success(Unit)
        }
        try {
            val conditionsBuilder = DownloadConditions.Builder()
            if (requireWifi) {
                conditionsBuilder.requireWifi()
            }
            val conditions = conditionsBuilder.build()

            val enDownloaded = runCatching { modelManager.isModelDownloaded(englishModel).awaitResult() }.getOrDefault(false)
            val zhDownloaded = runCatching { modelManager.isModelDownloaded(chineseModel).awaitResult() }.getOrDefault(false)

            val queue = mutableListOf<Pair<TranslateRemoteModel, String>>()
            if (!enDownloaded) queue.add(englishModel to "英语语言模型 (en，约 30MB)")
            if (!zhDownloaded) queue.add(chineseModel to "中文语言模型 (zh，约 30MB)")

            if (queue.isEmpty()) {
                _status.value = TranslationModelStatus.Ready
                return@withContext Result.success(Unit)
            }

            val totalSteps = queue.size

            for (i in queue.indices) {
                val (model, modelName) = queue[i]
                val currentStep = i + 1
                val baseProgress = i.toFloat() / totalSteps.toFloat()
                val stepWeight = 1f / totalSteps.toFloat()

                _status.value = TranslationModelStatus.Downloading(
                    progress = baseProgress + 0.05f * stepWeight,
                    step = currentStep,
                    totalSteps = totalSteps,
                    stepName = "正在下载 $modelName",
                )

                // 启动进度轮询与渐进平滑估算协程
                val monitorJob = launch {
                    var simulatedIncrement = 0.05f
                    while (isActive) {
                        delay(250)
                        val dmProgress = queryDownloadProgress()
                        if (dmProgress != null && dmProgress.second > 0L) {
                            val dl = dmProgress.first
                            val tot = dmProgress.second
                            val subFrac = (dl.toFloat() / tot.toFloat()).coerceIn(0f, 0.99f)
                            val overall = baseProgress + subFrac * stepWeight
                            _status.value = TranslationModelStatus.Downloading(
                                progress = overall,
                                step = currentStep,
                                totalSteps = totalSteps,
                                stepName = "正在下载 $modelName",
                                downloadedBytes = dl,
                                totalBytes = tot,
                            )
                        } else {
                            if (simulatedIncrement < 0.90f) {
                                simulatedIncrement += 0.03f
                            }
                            val overall = baseProgress + simulatedIncrement * stepWeight
                            _status.value = TranslationModelStatus.Downloading(
                                progress = overall,
                                step = currentStep,
                                totalSteps = totalSteps,
                                stepName = "正在下载 $modelName",
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                            )
                        }
                    }
                }

                try {
                    modelManager.download(model, conditions).awaitResult()
                } finally {
                    monitorJob.cancel()
                }

                val completedProgress = (i + 1).toFloat() / totalSteps.toFloat()
                _status.value = TranslationModelStatus.Downloading(
                    progress = completedProgress,
                    step = currentStep,
                    totalSteps = totalSteps,
                    stepName = "$modelName 下载完成",
                )
            }

            _status.value = TranslationModelStatus.Ready
            appLogger.i("TranslationManager: ML Kit translation models downloaded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            appLogger.e("TranslationManager: Download translation models failed", e)
            val msg = e.localizedMessage ?: "下载失败，若网络受限请检查连接或代理设置后重试"
            _status.value = TranslationModelStatus.Error(msg)
            Result.failure(e)
        } finally {
            downloadMutex.unlock()
        }
    }

    /** 删除本地已下载的模型包以释放存储空间 */
    suspend fun deleteModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            modelManager.deleteDownloadedModel(englishModel).awaitResult()
            modelManager.deleteDownloadedModel(chineseModel).awaitResult()
            _status.value = TranslationModelStatus.NotDownloaded
            translationCache.clear()
            appLogger.i("TranslationManager: ML Kit translation models deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            appLogger.e("TranslationManager: Delete translation models failed", e)
            val msg = e.localizedMessage ?: "删除模型失败"
            _status.value = TranslationModelStatus.Error(msg)
            Result.failure(e)
        }
    }

    /** 执行文本离线翻译（英文 -> 中文） */
    suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success("")

        val cacheKey = text.hashCode()
        translationCache[cacheKey]?.let {
            return@withContext Result.success(it)
        }

        if (_status.value !is TranslationModelStatus.Ready) {
            val enReady = runCatching { modelManager.isModelDownloaded(englishModel).awaitResult() }.getOrDefault(false)
            val zhReady = runCatching { modelManager.isModelDownloaded(chineseModel).awaitResult() }.getOrDefault(false)
            if (enReady && zhReady) {
                _status.value = TranslationModelStatus.Ready
            } else {
                return@withContext Result.failure(IllegalStateException("离线翻译模型尚未下载"))
            }
        }

        try {
            val translated = translator.translate(text).awaitResult()
            translationCache[cacheKey] = translated
            Result.success(translated)
        } catch (e: Exception) {
            appLogger.e("TranslationManager: Translation execution failed", e)
            Result.failure(e)
        }
    }

    fun isReady(): Boolean = _status.value is TranslationModelStatus.Ready

    override fun close() {
        try {
            translator.close()
        } catch (e: Exception) {
            appLogger.w("TranslationManager: Close translator error", e)
        }
    }
}

/** 协程桥接 Task<T> 扩展 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}

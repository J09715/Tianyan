package top.tianyan.app

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.getKoin
import top.tianyan.app.core.tools.backup.LocalBackupService
import java.io.File

/** Finish recovery before screens, bootstrap jobs or agents can observe half-restored settings. */
internal fun Application.recoverPendingDataBackup() {
    val file = File(filesDir, "backup-state/pending.json")
    if (file.exists() || File(file.path + ".bak").exists()) {
        runBlocking(Dispatchers.IO) { getKoin().get<LocalBackupService>().recoverPending() }
    }
}

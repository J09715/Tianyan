package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.edit
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * DataStore 依赖"rename 覆盖旧文件"的原子写语义：POSIX（CI/设备）上 rename 会替换已存在的
 * 目标，Windows 上目标存在即失败——`Unable to rename *.preferences_pb.tmp` 因此只出现在
 * Windows 本机（见 docs/KNOWN_ISSUES.md）。挂上本 Rule 的用例在探测不到该能力时显式跳过
 * （AssumptionViolatedException ≠ 失败），CI/Linux 的覆盖不受影响。
 */
class RequiresAtomicRenameOverwrite : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeTrue(
                "本机不支持 rename 覆盖已存在目标（Windows）；DataStore 原子写用例仅在 POSIX 环境执行",
                renameOverwriteWorks(),
            )
            base.evaluate()
        }
    }

    companion object {
        @Volatile
        private var cached: Boolean? = null

        /** 一次性探测：向已存在的目标 rename 是否成功（POSIX true / Windows false）。 */
        @JvmStatic
        fun renameOverwriteWorks(): Boolean {
            cached?.let { return it }
            val dir = File.createTempFile("rename-probe", null).apply { delete(); mkdirs() }
            val source = File(dir, "source")
            val target = File(dir, "target")
            source.writeText("s")
            target.writeText("t")
            val works = source.renameTo(target) && target.readText() == "s"
            dir.deleteRecursively()
            cached = works
            return works
        }
    }
}

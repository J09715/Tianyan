package top.tianyan.app.core.network

/**
 * 语义化版本（SemVer 2.0.0）比较器，支持 prerelease 后缀（如 1.0.0-rc.1）。
 *
 * 核心规则：数字主体按段比较；主体相等时预发布版低于正式版（1.0.0-rc.1 < 1.0.0），
 * 保证预发布用户能收到正式版升级提示，正式版用户不会被预发布"降级"覆盖。
 */
internal object VersionCompare {

    /** latest > current 返回 true */
    fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val (latestCore, latestPre) = splitPrerelease(latest.trim())
        val (currentCore, currentPre) = splitPrerelease(current.trim())

        val latestParts = numericParts(latestCore)
        val currentParts = numericParts(currentCore)
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }

        // 数字主体一致：semver 规定正式版高于任何预发布版
        if (latestPre == null && currentPre != null) return true
        if (latestPre == null || currentPre == null) return false
        return comparePrerelease(latestPre, currentPre) > 0
    }

    /** 拆分主体与预发布段："1.0.0-rc.1" → ("1.0.0", "rc.1") */
    private fun splitPrerelease(version: String): Pair<String, String?> {
        val dash = version.indexOf('-')
        return if (dash < 0) {
            version to null
        } else {
            version.substring(0, dash) to version.substring(dash + 1)
        }
    }

    private fun numericParts(core: String): List<Int> =
        core.split('.').map { it.toIntOrNull() ?: 0 }

    /** semver 预发布段逐位比较：数字段按数值、字母段按字典序，数字段 < 字母段，缺段更小 */
    private fun comparePrerelease(a: String, b: String): Int {
        val aSegs = a.split('.')
        val bSegs = b.split('.')
        for (i in 0 until maxOf(aSegs.size, bSegs.size)) {
            val aSeg = aSegs.getOrElse(i) { "" }
            val bSeg = bSegs.getOrElse(i) { "" }
            val aNum = aSeg.toIntOrNull()
            val bNum = bSeg.toIntOrNull()
            val cmp = when {
                aSeg.isEmpty() && bSeg.isEmpty() -> 0
                aSeg.isEmpty() -> -1
                bSeg.isEmpty() -> 1
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1
                bNum != null -> 1
                else -> aSeg.compareTo(bSeg)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}

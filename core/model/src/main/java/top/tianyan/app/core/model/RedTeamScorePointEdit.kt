package top.tianyan.app.core.model

/**
 * 用户对得分点的编辑，移植自上游 `saveScorePoint` / `deleteScorePoint` 的语义。
 *
 * 为什么内置点不能改分值：同一条规则的上限是按**组内所有得分点累计**的
 * （见 [RedTeamScoring.applyScoreCaps]），单独调高一个点的分值会让一条命中吃掉整组上限，
 * 规则表就不再是分发的那份了。所以内置点只允许「启用/停用」，要自定义分值请**新增一个点**。
 *
 * 内置目录来自 [RedTeamScoreCatalog]（随规则分发、不可变），这里只承载**覆盖层**。
 */
object RedTeamScorePointEdit {

    /** 内置得分点被锁定的字段。 */
    val LOCKED_FIELDS = listOf("name", "category", "points", "cap", "rule", "tier", "dedup_scope", "description")

    /** 一个自建得分点；`code` 为空表示调用方没给，由服务端按名称生成。 */
    data class CustomPoint(
        val code: String?,
        val name: String,
        val category: String? = null,
        val points: Int = 0,
        val description: String? = null,
        val enabled: Boolean = true,
    )

    /** 内置点的启停覆盖：code → 是否启用。 */
    data class Overrides(
        val builtinEnabled: Map<String, Boolean> = emptyMap(),
        val custom: List<CustomPoint> = emptyList(),
        /** 被停用的自建点 code。 */
        val disabledCustom: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = builtinEnabled.isEmpty() && custom.isEmpty() && disabledCustom.isEmpty()
    }

    data class SaveResult(
        val code: String,
        val updated: Boolean,
        val builtin: Boolean,
        val note: String,
        val lockedFields: List<String> = emptyList(),
    )

    /**
     * 保存一个得分点。
     *
     * 顺序很关键：**内置点分支必须放在 name 必填校验之前**——界面上的启用开关与
     * 「只停用某个内置点」的调用方本来就不该被强制回传 name。
     */
    fun save(
        existingCode: String?,
        name: String?,
        enabled: Boolean?,
        category: String? = null,
        points: Int? = null,
        description: String? = null,
        takenCodes: Set<String>,
    ): Pair<SaveResult, Overrides.() -> Overrides> {
        val builtin = existingCode?.let { RedTeamScoring.POINTS_BY_CODE[it] } != null
        if (builtin && existingCode != null) {
            val on = enabled ?: true
            val note = "这是随《突破入侵类得分规则》分发的内置得分点：分值、上限、计分口径、名称与条款正文" +
                "都由规则锁定（同一条规则的上限按组内所有得分点累计，单独改分值会让一条命中吃掉整组上限）。" +
                "已保存你修改的「启用/停用」。要自定义分值时请**新增一个得分点**。"
            return SaveResult(
                code = existingCode,
                updated = true,
                builtin = true,
                note = note,
                lockedFields = LOCKED_FIELDS,
            ) to { copy(builtinEnabled = builtinEnabled + (existingCode to on)) }
        }

        val cleanName = name?.trim().orEmpty()
        require(cleanName.isNotEmpty()) { "score point name required" }

        // 自建点可能带上与内置点相同的 code（界面默认按名称生成）：撞了就换一个，
        // 避免「新增失败但界面看不出原因」。
        val requested = existingCode?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedCode = when {
            requested == null -> null
            requested in takenCodes -> "$requested-custom-" + System.currentTimeMillis().toString(36)
            else -> requested
        }
        val note = if (requested == null) {
            "已新增自建得分点（不受规则分值锁定）"
        } else {
            "已保存（自建得分点）"
        }
        return SaveResult(
            code = resolvedCode ?: slugCode(cleanName, takenCodes),
            updated = requested != null,
            builtin = false,
            note = note,
        ) to {
            val entry = CustomPoint(
                code = resolvedCode ?: slugCode(cleanName, takenCodes),
                name = cleanName,
                category = category,
                points = points ?: 0,
                description = description,
                enabled = enabled ?: true,
            )
            copy(custom = custom.filterNot { it.code == entry.code } + entry)
        }
    }

    /** 内置点不能删除：删掉会让规则表缺一条、报告少一类成果。 */
    fun assertDeletable(code: String) {
        val point = RedTeamScoring.POINTS_BY_CODE[code]
        if (point != null) {
            throw IllegalArgumentException(
                "内置得分点不能删除：「${point.name}」来自《突破入侵类得分规则（合并版）》，" +
                    "删掉会让面板缺一条规则、报告少一类成果。要让它不参与计分，请改用「停用」。",
            )
        }
    }

    /**
     * 把覆盖层合并进得分点表，供评分引擎使用。
     *
     * 这一步是**必须**的：只把自建点存起来而不合并，自建点就永远不参与计分——
     * 界面上看得见、算分时当不存在，是比没有这个功能更难查的问题。
     */
    fun mergeInto(base: Map<String, ScorePoint> = RedTeamScoring.POINTS_BY_CODE, overrides: Overrides): Map<String, ScorePoint> {
        if (overrides.isEmpty) return base
        val merged = base.toMutableMap()
        overrides.builtinEnabled.forEach { (code, on) ->
            merged[code]?.let { merged[code] = it.copy(enabled = on) }
        }
        overrides.custom.forEach { custom ->
            val code = custom.code ?: return@forEach
            val existing = merged[code]
            merged[code] = ScorePoint(
                // 自建点没有规则来源；上限按自身分组记账（capGroup 会退化成 `code:<code>`），
                // 这样自建点之间互不吃上限，也不会去挤内置规则的组。
                src = existing?.src ?: 0,
                rule = existing?.rule ?: 0,
                tier = existing?.tier ?: "自建",
                code = code,
                name = custom.name,
                category = custom.category ?: existing?.category ?: "自建",
                points = custom.points,
                cap = existing?.cap ?: 0,
                dedupScope = existing?.dedupScope ?: ScoreDedupScope.NONE,
                description = custom.description ?: existing?.description.orEmpty(),
                enabled = custom.enabled && code !in overrides.disabledCustom,
            )
        }
        overrides.disabledCustom.forEach { code ->
            merged[code]?.let { merged[code] = it.copy(enabled = false) }
        }
        return merged
    }

    /** 按名称生成一个可读 code，撞了就加后缀。 */
    fun slugCode(name: String, taken: Set<String>): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifEmpty { "custom" }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }
}
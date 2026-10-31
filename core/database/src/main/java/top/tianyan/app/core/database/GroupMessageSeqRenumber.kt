package top.tianyan.app.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Renumbers group message seq contiguously per group by rowid order in a single pass.
 * A correlated COUNT(*) UPDATE would rescan the group for every row (O(n²)).
 */
internal fun renumberGroupMessages(db: SupportSQLiteDatabase) {
    val rows = ArrayList<Pair<String, Long>>()
    db.query(
        "SELECT `groupId`, `rowid` FROM `group_messages` ORDER BY `groupId`, `rowid`",
    ).use { cursor ->
        while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getLong(1)
    }
    val update = db.compileStatement("UPDATE `group_messages` SET `seq` = ? WHERE `rowid` = ?")
    try {
        var groupId: String? = null
        var seq = 0L
        for ((rowGroupId, rowid) in rows) {
            if (rowGroupId != groupId) {
                groupId = rowGroupId
                seq = 0
            }
            seq += 1
            update.clearBindings()
            update.bindLong(1, seq)
            update.bindLong(2, rowid)
            update.executeUpdateDelete()
        }
    } finally {
        update.close()
    }
}

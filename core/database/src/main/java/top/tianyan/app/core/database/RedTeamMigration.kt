package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 会话级红队绑定字段。
 *
 * 全部带默认值：老会话升级后一律是「未开启红队」，不会因为新增列而获得红队能力。
 * 这是有意的——红队是显式开启的能力，不能靠升级默认打开。
 */
val MIGRATION_83_84 = object : Migration(83, 84) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN redTeamMode TEXT NOT NULL DEFAULT 'off'")
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN redTeamTarget TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN redTeamScope TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE harness_sessions ADD COLUMN redTeamPhase TEXT NOT NULL DEFAULT 'idle'")
    }
}

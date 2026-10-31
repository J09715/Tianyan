package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `harness_sessions` ADD COLUMN `executionTargetJson` TEXT DEFAULT 'unbound'")
    }
}

val MIGRATION_55_56 = object : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `terminal_sessions` ADD COLUMN `executionTargetJson` TEXT DEFAULT NULL")
    }
}

package top.tianyan.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing member selections stay fixed until the user explicitly allows self-service switching. */
val MIGRATION_72_73 = object : Migration(72, 73) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `persona_bots` ADD COLUMN `allowModelSelection` INTEGER NOT NULL DEFAULT 0")
    }
}

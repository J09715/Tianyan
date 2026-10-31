package top.tianyan.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Written in the records transaction, so startup can distinguish commit from rollback. */
@Entity(tableName = "backup_restore_receipt")
data class BackupRestoreReceipt(@PrimaryKey val id: Int = 1, val operationId: String)

val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `backup_restore_receipt` (`id` INTEGER NOT NULL, `operationId` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}

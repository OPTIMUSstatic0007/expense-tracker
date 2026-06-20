package com.example.expensetracker.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.expensetracker.cloud.PendingSyncConverters
import com.example.expensetracker.cloud.PendingSyncDao
import com.example.expensetracker.cloud.PendingSyncOperation

@Database(entities = [TransactionEntity::class, PendingSyncOperation::class], version = 4, exportSchema = false)
@TypeConverters(PendingSyncConverters::class)
abstract class ExpenseDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun pendingSyncDao(): PendingSyncDao

    companion object {
        @Volatile
        private var INSTANCE: ExpenseDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sequenceId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_sequenceId` ON `transactions` (`sequenceId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_sync_operations` (
                        `id` TEXT NOT NULL,
                        `transactionId` TEXT NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `version` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `lastAttemptAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_operations_ownerUid` ON `pending_sync_operations` (`ownerUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_operations_status` ON `pending_sync_operations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_operations_createdAt` ON `pending_sync_operations` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_sync_operations_transactionId` ON `pending_sync_operations` (`transactionId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): ExpenseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ExpenseDatabase::class.java,
                    "expense_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }
}

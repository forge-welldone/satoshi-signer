package com.remotesigner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class InboxStatusConverter {
    @TypeConverter
    fun fromStatus(status: InboxStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): InboxStatus = try {
        InboxStatus.valueOf(value)
    } catch (_: IllegalArgumentException) {
        InboxStatus.PENDING
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inbox_items (
                id TEXT NOT NULL PRIMARY KEY,
                psbtBytes BLOB NOT NULL,
                label TEXT NOT NULL,
                amount TEXT NOT NULL,
                senderNpub TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                status TEXT NOT NULL,
                rawHex TEXT,
                txid TEXT,
                network TEXT NOT NULL
            )
        """.trimIndent())
    }
}

@Database(
    entities = [Contact::class, ContactFingerprint::class, InboxItemEntity::class],
    version = 2,
)
@TypeConverters(InboxStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun inboxDao(): InboxDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "satoshi-signer.db",
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}

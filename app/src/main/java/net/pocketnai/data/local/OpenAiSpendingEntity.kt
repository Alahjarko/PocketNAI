package net.pocketnai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** One request, one ledger row. No foreign key: deleting an image must not erase its cost. */
@Entity(tableName = "openai_spending")
data class OpenAiSpendingEntity(
    @PrimaryKey val generationId: String,
    val createdAt: Long,
    val status: String,
    val modelApiId: String,
    val snapshotJson: String,
) {
    companion object {
        fun install(db: SupportSQLiteDatabase, backfill: Boolean = false) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS openai_spending (
                generationId TEXT NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL,
                status TEXT NOT NULL, modelApiId TEXT NOT NULL, snapshotJson TEXT NOT NULL)""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS openai_spending_insert AFTER INSERT ON generations
                WHEN NEW.openAiParamsJson IS NOT NULL BEGIN
                INSERT OR REPLACE INTO openai_spending(generationId, createdAt, status, modelApiId, snapshotJson)
                VALUES(NEW.id, NEW.createdAt, NEW.status, NEW.modelApiId, NEW.openAiParamsJson); END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS openai_spending_update AFTER UPDATE OF openAiParamsJson, status ON generations
                WHEN NEW.openAiParamsJson IS NOT NULL BEGIN
                INSERT OR REPLACE INTO openai_spending(generationId, createdAt, status, modelApiId, snapshotJson)
                VALUES(NEW.id, NEW.createdAt, NEW.status, NEW.modelApiId, NEW.openAiParamsJson); END""")
            if (backfill) db.execSQL("""INSERT OR IGNORE INTO openai_spending(generationId, createdAt, status, modelApiId, snapshotJson)
                SELECT id, createdAt, status, modelApiId, openAiParamsJson FROM generations WHERE openAiParamsJson IS NOT NULL""")
        }

        val callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = install(db)
            override fun onOpen(db: SupportSQLiteDatabase) = install(db)
        }
    }
}

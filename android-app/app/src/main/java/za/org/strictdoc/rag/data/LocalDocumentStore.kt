package za.org.strictdoc.rag.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "document_queue")
data class DocumentRecord(
    @PrimaryKey val localId: String,
    val filename: String,
    val localFilePath: String,
    val remoteDocId: String,
    val chunkCount: Int,
    val deviceTimestampUtc: Long,
    val syncState: String // "QUEUED" | "SYNCED" | "FAILED"
)

@Entity(tableName = "saved_answers")
data class SavedAnswerRecord(
    @PrimaryKey val id: String,
    val queryText: String,
    val status: String,
    val usedExternal: Boolean,
    val formattedBody: String,
    val savedTimestampUtc: Long
)

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(record: DocumentRecord)

    @Query("SELECT * FROM document_queue WHERE syncState = 'QUEUED' ORDER BY deviceTimestampUtc ASC")
    suspend fun getPendingDocuments(): List<DocumentRecord>

    @Query("SELECT * FROM document_queue ORDER BY deviceTimestampUtc DESC LIMIT 30")
    suspend fun getAllDocuments(): List<DocumentRecord>

    @Query("UPDATE document_queue SET syncState = :state, remoteDocId = :remoteId, chunkCount = :chunks WHERE localId = :localId")
    suspend fun updateSyncResult(localId: String, state: String, remoteId: String, chunks: Int)
}

@Dao
interface SavedAnswerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswer(record: SavedAnswerRecord)

    @Query("SELECT * FROM saved_answers ORDER BY savedTimestampUtc DESC LIMIT 50")
    suspend fun getAllSaved(): List<SavedAnswerRecord>

    @Query("DELETE FROM saved_answers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [DocumentRecord::class, SavedAnswerRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun savedAnswerDao(): SavedAnswerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "strictdoc_ledger.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
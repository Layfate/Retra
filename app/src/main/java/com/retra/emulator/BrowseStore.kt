package com.retra.emulator

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import org.json.JSONObject

@Entity(tableName = "browse_documents", primaryKeys = ["kind", "id"])
data class BrowseDocument(val kind: String, val id: String, val json: String)

@Dao
interface BrowseDao {
    @Transaction
    fun read(kind: String, id: String): String? {
        val length = length(kind, id) ?: return null
        // Read one snapshot in bounded chunks to fit Android's SQLite CursorWindow.
        return buildString { for (offset in 1..length step 131072) append(chunk(kind, id, offset)) }
    }
    @Query("SELECT length(json) FROM browse_documents WHERE kind = :kind AND id = :id")
    fun length(kind: String, id: String): Int?
    @Query("SELECT substr(json, :offset, 131072) FROM browse_documents WHERE kind = :kind AND id = :id")
    fun chunk(kind: String, id: String, offset: Int): String
    @Query("SELECT id FROM browse_documents WHERE kind = :kind ORDER BY id")
    fun ids(kind: String): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: BrowseDocument)
    @Query("DELETE FROM browse_documents WHERE kind = :kind AND id = :id")
    fun delete(kind: String, id: String)
}

@Database(entities = [BrowseDocument::class], version = 1, exportSchema = true)
abstract class BrowseDatabase : RoomDatabase() {
    abstract fun documents(): BrowseDao
    companion object {
        @Volatile private var instance: BrowseDatabase? = null
        fun get(context: Context): BrowseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, BrowseDatabase::class.java, "retra_browse.db")
                .build().also { instance = it }
        }
    }
}

class BrowseStore(private val dao: BrowseDao) {
    fun get(kind: String, id: String): JSONObject? = dao.read(kind, id)?.let { JSONObject(it) }
    fun all(kind: String) = dao.ids(kind).mapNotNull { get(kind, it) }
    fun put(kind: String, id: String, value: JSONObject) = dao.put(BrowseDocument(kind, id, value.toString()))
    fun delete(kind: String, id: String) = dao.delete(kind, id)
}

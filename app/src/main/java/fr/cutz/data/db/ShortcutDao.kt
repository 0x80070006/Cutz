package fr.cutz.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM shortcuts")
    suspend fun getAll(): List<ShortcutEntity>

    @Query("SELECT * FROM shortcuts WHERE id = :id")
    suspend fun getById(id: String): ShortcutEntity?

    @Query("SELECT * FROM shortcuts WHERE type = 'APP' AND target = :packageName")
    suspend fun getByPackage(packageName: String): List<ShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShortcutEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ShortcutEntity>)

    @Update
    suspend fun update(entity: ShortcutEntity)

    @Delete
    suspend fun delete(entity: ShortcutEntity)
}

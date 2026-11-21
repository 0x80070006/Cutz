package fr.cutz.data

import fr.cutz.data.db.ShortcutDao
import fr.cutz.data.db.ShortcutEntity
import kotlinx.coroutines.flow.Flow

/** Abstraction over the DAO so ViewModels can be unit-tested with an in-memory fake. */
interface ShortcutRepository {
    fun observeAll(): Flow<List<ShortcutEntity>>
    suspend fun getAll(): List<ShortcutEntity>
    suspend fun get(id: String): ShortcutEntity?
    suspend fun getByPackage(packageName: String): List<ShortcutEntity>
    suspend fun save(entity: ShortcutEntity)
    suspend fun saveAll(entities: List<ShortcutEntity>)
    suspend fun delete(entity: ShortcutEntity)
}

class RoomShortcutRepository(private val dao: ShortcutDao) : ShortcutRepository {
    override fun observeAll() = dao.observeAll()
    override suspend fun getAll() = dao.getAll()
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun getByPackage(packageName: String) = dao.getByPackage(packageName)
    override suspend fun save(entity: ShortcutEntity) = dao.upsert(entity)
    override suspend fun saveAll(entities: List<ShortcutEntity>) = dao.upsertAll(entities)
    override suspend fun delete(entity: ShortcutEntity) = dao.delete(entity)
}

package fr.cutz

import fr.cutz.data.ShortcutRepository
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.shortcut.PublishResult
import fr.cutz.shortcut.ShortcutPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeRepository(initial: List<ShortcutEntity> = emptyList()) : ShortcutRepository {
    val items = MutableStateFlow(initial.associateBy { it.id })
    override fun observeAll() = items.map { it.values.toList() }
    override suspend fun getAll() = items.value.values.toList()
    override suspend fun get(id: String) = items.value[id]
    override suspend fun getByPackage(packageName: String) = items.value.values.filter { it.target == packageName }
    override suspend fun save(entity: ShortcutEntity) = items.update { it + (entity.id to entity) }
    override suspend fun saveAll(entities: List<ShortcutEntity>) = items.update { m -> m + entities.associateBy { it.id } }
    override suspend fun delete(entity: ShortcutEntity) = items.update { it - entity.id }
}

class FakePublisher : ShortcutPublisher {
    var pinSupported = true
    var pinResult: PublishResult = PublishResult.Ok
    var updateResult: PublishResult = PublishResult.Ok
    val pinned = mutableSetOf<String>()
    val disabled = mutableMapOf<String, String>()
    val enabled = mutableListOf<String>()
    val pinRequests = mutableListOf<ShortcutEntity>()
    val updates = mutableListOf<ShortcutEntity>()

    override fun isPinSupported() = pinSupported
    override suspend fun requestPin(entity: ShortcutEntity): PublishResult {
        pinRequests += entity
        return pinResult
    }
    override suspend fun update(entities: List<ShortcutEntity>): PublishResult {
        if (updateResult == PublishResult.Ok) updates += entities
        return updateResult
    }
    override fun disable(ids: List<String>, message: String) = ids.forEach { disabled[it] = message }
    override fun enable(ids: List<String>) { enabled += ids; ids.forEach { disabled.remove(it) } }
    override fun pinnedIds(): Set<String> = pinned.toSet()
}

fun shortcut(
    id: String = "id-1",
    type: TargetType = TargetType.URL,
    target: String = "https://example.org",
    status: ShortcutStatus = ShortcutStatus.ACTIF,
    reason: fr.cutz.data.db.DisabledReason? = null,
    updatedAt: Long = 0,
    pendingPush: Boolean = false,
) = ShortcutEntity(
    id = id, type = type, target = target, name = "Example",
    iconSource = IconSourceType.BUNDLED, iconRef = "tabler/home",
    backgroundColor = 0xFF3949AB.toInt(), glyphColor = -1, shape = IconShape.SYSTEM,
    scale = 0.6f, margin = 0f, status = status, disabledReason = reason, pendingPush = pendingPush,
    createdAt = 0, updatedAt = updatedAt,
)

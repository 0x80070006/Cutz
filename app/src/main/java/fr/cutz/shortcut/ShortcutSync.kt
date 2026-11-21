package fr.cutz.shortcut

import fr.cutz.data.ShortcutRepository
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the database with what the launcher actually holds. Runs at start-up,
 * on every resume, after a pin callback and after an app is uninstalled.
 *
 * State machine (status / reason):
 *  - ACTIF, missing from pinned set (after [graceMs])      -> DESACTIVE / NOT_PINNED
 *  - DESACTIVE / NOT_PINNED, back in pinned set            -> ACTIF
 *  - APP target uninstalled                                -> disable + DESACTIVE / APP_UNINSTALLED
 *  - APP_UNINSTALLED, app installed again                  -> enable + ACTIF (or NOT_PINNED)
 *  - DELETED_BY_USER                                       -> untouched; [pinnedIds] tells the UI
 *                                                             whether the grey icon is still on home
 *  - pendingPush (update refused by the rate limiter)      -> retried in one batched update
 */
class ShortcutSync(
    private val repo: ShortcutRepository,
    private val publisher: ShortcutPublisher,
    private val isInstalled: (String) -> Boolean,
    private val uninstalledMessage: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A pin dialog may still be open: do not flag a brand-new shortcut as unpinned yet. */
    private val graceMs: Long = 4_000,
) {
    private val mutex = Mutex()
    private val _pinnedIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds
    private var delayed: Job? = null

    suspend fun sync() = mutex.withLock {
        val pinned = runCatching { publisher.pinnedIds() }.getOrDefault(_pinnedIds.value)
        _pinnedIds.value = pinned
        val now = clock()
        val changed = mutableListOf<ShortcutEntity>()
        val toDisable = mutableListOf<String>()
        val toEnable = mutableListOf<String>()
        val toPush = mutableListOf<ShortcutEntity>()

        for (e in repo.getAll()) {
            val isPinned = e.id in pinned
            val appMissing = e.type == TargetType.APP && !isInstalled(e.target)
            val next: ShortcutEntity? = when {
                e.disabledReason == DisabledReason.DELETED_BY_USER -> null

                appMissing && e.disabledReason != DisabledReason.APP_UNINSTALLED -> {
                    if (isPinned) toDisable += e.id
                    e.copy(status = ShortcutStatus.DESACTIVE, disabledReason = DisabledReason.APP_UNINSTALLED, updatedAt = now)
                }

                e.disabledReason == DisabledReason.APP_UNINSTALLED && !appMissing -> {
                    if (isPinned) toEnable += e.id
                    if (isPinned) e.copy(status = ShortcutStatus.ACTIF, disabledReason = null, updatedAt = now)
                    else e.copy(disabledReason = DisabledReason.NOT_PINNED, updatedAt = now)
                }

                e.status == ShortcutStatus.ACTIF && !isPinned && now - e.updatedAt > graceMs ->
                    e.copy(status = ShortcutStatus.DESACTIVE, disabledReason = DisabledReason.NOT_PINNED, updatedAt = now)

                e.disabledReason == DisabledReason.NOT_PINNED && isPinned ->
                    e.copy(status = ShortcutStatus.ACTIF, disabledReason = null, updatedAt = now)

                else -> null
            }
            val current = next ?: e
            if (next != null) changed += next
            if (current.pendingPush && current.status == ShortcutStatus.ACTIF && isPinned) toPush += current
        }

        publisher.disable(toDisable, uninstalledMessage())
        publisher.enable(toEnable)
        if (toPush.isNotEmpty() && publisher.update(toPush) == PublishResult.Ok) {
            val pushedIds = toPush.map { it.id }.toSet()
            for (i in changed.indices) if (changed[i].id in pushedIds) changed[i] = changed[i].copy(pendingPush = false)
            val changedIds = changed.map { it.id }.toSet()
            changed += toPush.filter { it.id !in changedIds }.map { it.copy(pendingPush = false) }
        }
        if (changed.isNotEmpty()) repo.saveAll(changed)
    }

    /** Re-check shortly after a pin request, once the launcher dialog has been answered. */
    fun syncSoon(scope: CoroutineScope) {
        delayed?.cancel()
        delayed = scope.launch {
            delay(graceMs + 500)
            sync()
        }
    }
}

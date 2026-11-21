package fr.cutz.ui.shortcuts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.cutz.data.ShortcutRepository
import fr.cutz.data.backup.BackupPort
import fr.cutz.data.backup.ImportReport
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.shortcut.PublishResult
import fr.cutz.shortcut.ShortcutPublisher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Entry waiting for manual removal; [stillOnHome] turns false once the launcher no longer holds it. */
data class PendingRemoval(val entity: ShortcutEntity, val stillOnHome: Boolean)

data class ShortcutsUiState(
    val loading: Boolean = true,
    val active: List<ShortcutEntity> = emptyList(),
    val notPinned: List<ShortcutEntity> = emptyList(),
    val appMissing: List<ShortcutEntity> = emptyList(),
    val toRemove: List<PendingRemoval> = emptyList(),
) {
    val isEmpty get() = !loading && active.isEmpty() && notPinned.isEmpty() && appMissing.isEmpty() && toRemove.isEmpty()
}

sealed interface ShortcutsEvent {
    data class Disabled(val name: String) : ShortcutsEvent
    data class Purged(val name: String) : ShortcutsEvent
    data object PinRequested : ShortcutsEvent
    data object PinUnsupported : ShortcutsEvent
    data class Exported(val count: Int) : ShortcutsEvent
    data class Imported(val report: ImportReport) : ShortcutsEvent
    data class Error(val message: String) : ShortcutsEvent
}

class ShortcutsViewModel(
    private val repo: ShortcutRepository,
    private val publisher: ShortcutPublisher,
    pinnedIds: Flow<Set<String>>,
    private val refreshPinned: suspend () -> Unit,
    private val deletedMessage: String,
    private val deleteGalleryFile: (String) -> Unit = {},
    private val backup: BackupPort? = null,
    private val onPinRequested: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _events = Channel<ShortcutsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<ShortcutsUiState> = combine(repo.observeAll(), pinnedIds) { all, pinned ->
        ShortcutsUiState(
            loading = false,
            active = all.filter { it.status == ShortcutStatus.ACTIF },
            notPinned = all.filter { it.status == ShortcutStatus.DESACTIVE && it.disabledReason == DisabledReason.NOT_PINNED },
            appMissing = all.filter { it.disabledReason == DisabledReason.APP_UNINSTALLED },
            toRemove = all.filter { it.disabledReason == DisabledReason.DELETED_BY_USER }
                .map { PendingRemoval(it, stillOnHome = it.id in pinned) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortcutsUiState())

    fun refresh() = viewModelScope.launch { refreshPinned() }

    /**
     * "Supprimer". A launcher icon cannot be removed programmatically, so a pinned
     * shortcut is disabled (greyed on the home screen, launching it shows the message)
     * and parked in "À retirer manuellement". A shortcut that is not on the home screen
     * at all is deleted right away.
     */
    fun delete(entity: ShortcutEntity) = viewModelScope.launch {
        val pinned = runCatching { publisher.pinnedIds() }.getOrDefault(emptySet())
        if (entity.id !in pinned) {
            purgeNow(entity); return@launch
        }
        publisher.disable(listOf(entity.id), deletedMessage)
        repo.save(entity.copy(status = ShortcutStatus.DESACTIVE, disabledReason = DisabledReason.DELETED_BY_USER, updatedAt = clock()))
        refreshPinned()
        _events.send(ShortcutsEvent.Disabled(entity.name))
    }

    /** Undo a deletion while the greyed icon is still on the home screen. */
    fun restore(entity: ShortcutEntity) = viewModelScope.launch {
        val pinned = runCatching { publisher.pinnedIds() }.getOrDefault(emptySet())
        if (entity.id in pinned) {
            publisher.enable(listOf(entity.id))
            repo.save(entity.copy(status = ShortcutStatus.ACTIF, disabledReason = null, updatedAt = clock()))
        } else {
            repo.save(entity.copy(status = ShortcutStatus.DESACTIVE, disabledReason = DisabledReason.NOT_PINNED, updatedAt = clock()))
        }
        refreshPinned()
    }

    /** "Supprimer définitivement de la liste", after the user confirmed removing the icon. */
    fun purge(entity: ShortcutEntity) = viewModelScope.launch { purgeNow(entity) }

    private suspend fun purgeNow(entity: ShortcutEntity) {
        repo.delete(entity)
        if (entity.iconSource == IconSourceType.GALLERY) runCatching { deleteGalleryFile(entity.iconRef) }
        _events.send(ShortcutsEvent.Purged(entity.name))
    }

    /** Pin again a NOT_PINNED shortcut (cancelled dialog, removed by hand, imported). */
    fun pin(entity: ShortcutEntity) = viewModelScope.launch {
        if (!publisher.isPinSupported()) {
            _events.send(ShortcutsEvent.PinUnsupported); return@launch
        }
        val active = entity.copy(status = ShortcutStatus.ACTIF, disabledReason = null, updatedAt = clock())
        when (val r = publisher.requestPin(active)) {
            PublishResult.Ok -> {
                repo.save(active); onPinRequested(); _events.send(ShortcutsEvent.PinRequested)
            }
            PublishResult.PinUnsupported -> _events.send(ShortcutsEvent.PinUnsupported)
            PublishResult.RateLimited -> _events.send(ShortcutsEvent.Error("rate limited"))
            is PublishResult.Error -> _events.send(ShortcutsEvent.Error(r.message))
        }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        val b = backup ?: return@launch
        runCatching { b.export(uri) }
            .onSuccess { _events.send(ShortcutsEvent.Exported(it)) }
            .onFailure { _events.send(ShortcutsEvent.Error(it.message ?: "export")) }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        val b = backup ?: return@launch
        runCatching { b.import(uri) }
            .onSuccess { _events.send(ShortcutsEvent.Imported(it)); refreshPinned() }
            .onFailure { _events.send(ShortcutsEvent.Error(it.message ?: "import")) }
    }
}

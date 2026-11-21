package fr.cutz.ui.create

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.cutz.data.ShortcutRepository
import fr.cutz.data.backup.BackupCodec
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.data.icons.IconMeta
import fr.cutz.shortcut.IconSpec
import fr.cutz.shortcut.PublishResult
import fr.cutz.shortcut.ShortcutPublisher
import fr.cutz.util.UrlValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

const val DEFAULT_BACKGROUND = 0xFF3949AB.toInt()
const val DEFAULT_GLYPH = 0xFFFFFFFF.toInt()

data class EditorState(
    /** Null while creating; the shortcut id while editing an existing one. */
    val editingId: String? = null,
    val editingStatus: ShortcutStatus? = null,
    val createdAt: Long? = null,
    val type: TargetType = TargetType.APP,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val url: String = "",
    val name: String = "",
    val nameEditedByUser: Boolean = false,
    val iconSource: IconSourceType? = null,
    val iconRef: String? = null,
    val iconTitle: String? = null,
    val backgroundColor: Int = DEFAULT_BACKGROUND,
    val glyphColor: Int = DEFAULT_GLYPH,
    val shape: IconShape = IconShape.SYSTEM,
    val scale: Float = 0.6f,
    val margin: Float = 0f,
    val busy: Boolean = false,
) {
    val normalizedUrl: String? get() = UrlValidator.normalize(url)
    val target: String? get() = if (type == TargetType.APP) appPackage else normalizedUrl
    val showUrlError: Boolean get() = type == TargetType.URL && url.isNotBlank() && normalizedUrl == null
    val canSubmit: Boolean get() = !busy && target != null && name.isNotBlank() && iconSource != null && iconRef != null
    val isEditing: Boolean get() = editingId != null

    fun spec(): IconSpec? {
        val src = iconSource ?: return null
        val ref = iconRef ?: return null
        return IconSpec(src, ref, backgroundColor, glyphColor, shape, scale, margin)
    }
}

sealed interface CreateEvent {
    data object PinRequested : CreateEvent
    data object PinUnsupported : CreateEvent
    data object Saved : CreateEvent
    data object SavedPending : CreateEvent
    data class Error(val message: String) : CreateEvent
}

/**
 * Create/edit form. Android-specific pieces (bitmap rendering, gallery storage, sync)
 * are injected as functions so the ViewModel is testable on the JVM with fakes.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CreateViewModel(
    private val repo: ShortcutRepository,
    private val publisher: ShortcutPublisher,
    private val renderPreview: suspend (IconSpec) -> Bitmap?,
    private val onIconUsed: (String) -> Unit = {},
    private val onPinRequested: () -> Unit = {},
    private val saveGalleryImage: (Bitmap) -> String = { error("gallery unavailable") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _events = Channel<CreateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Live preview, re-rendered (debounced) whenever an appearance field changes. */
    val preview: StateFlow<Bitmap?> = _state
        .map { it.spec() }
        .distinctUntilChanged()
        .debounce(60)
        .mapLatest { spec -> spec?.let { runCatching { renderPreview(it) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ target

    fun setType(type: TargetType) = _state.update { s ->
        if (s.type == type) s
        else s.copy(
            type = type,
            // The app's own icon makes no sense for a web link.
            iconSource = if (type == TargetType.URL && s.iconSource == IconSourceType.APP_ICON) null else s.iconSource,
            iconRef = if (type == TargetType.URL && s.iconSource == IconSourceType.APP_ICON) null else s.iconRef,
        )
    }

    fun selectApp(packageName: String, label: String) = _state.update { s ->
        val useAppIcon = s.iconSource == null || s.iconSource == IconSourceType.APP_ICON
        s.copy(
            appPackage = packageName,
            appLabel = label,
            name = if (s.nameEditedByUser) s.name else label.take(BackupCodec.MAX_NAME),
            iconSource = if (useAppIcon) IconSourceType.APP_ICON else s.iconSource,
            iconRef = if (useAppIcon) packageName else s.iconRef,
            // App icons carry their own colours; a light neutral backdrop suits most.
            backgroundColor = if (useAppIcon && s.iconSource == null) 0xFFFFFFFF.toInt() else s.backgroundColor,
            scale = if (useAppIcon && s.iconSource == null) 0.8f else s.scale,
        )
    }

    fun setUrl(url: String) = _state.update { s ->
        val suggested = UrlValidator.normalize(url)?.let { hostLabel(it) }
        s.copy(url = url, name = if (s.nameEditedByUser || suggested == null) s.name else suggested)
    }

    fun setName(name: String) = _state.update {
        it.copy(name = name.take(BackupCodec.MAX_NAME), nameEditedByUser = name.isNotEmpty())
    }

    // ------------------------------------------------------------------- icon

    fun selectBundledIcon(icon: IconMeta) {
        onIconUsed(icon.id)
        _state.update { s ->
            val brand = icon.brandColor
            s.copy(
                iconSource = IconSourceType.BUNDLED,
                iconRef = icon.id,
                iconTitle = icon.title,
                // Simple Icons ship the official brand colour: use it as a sensible default.
                backgroundColor = brand ?: s.backgroundColor,
                glyphColor = if (brand != null) contrastingGlyph(brand) else s.glyphColor,
                scale = if (s.iconSource == IconSourceType.BUNDLED) s.scale else 0.6f,
            )
        }
    }

    fun useAppIcon() = _state.update { s ->
        val pkg = s.appPackage ?: return@update s
        s.copy(iconSource = IconSourceType.APP_ICON, iconRef = pkg, iconTitle = null)
    }

    fun setGalleryImage(bitmap: Bitmap) {
        viewModelScope.launch {
            val name = runCatching { saveGalleryImage(bitmap) }.getOrElse {
                _events.send(CreateEvent.Error(it.message ?: "gallery")); return@launch
            }
            _state.update { it.copy(iconSource = IconSourceType.GALLERY, iconRef = name, iconTitle = null, scale = 1f, margin = 0f) }
        }
    }

    // ------------------------------------------------------------ appearance

    fun setBackground(color: Int) = _state.update { it.copy(backgroundColor = color) }
    fun setGlyphColor(color: Int) = _state.update { it.copy(glyphColor = color) }
    fun setShape(shape: IconShape) = _state.update { it.copy(shape = shape) }
    fun setScale(scale: Float) = _state.update { it.copy(scale = scale.coerceIn(0.3f, 1f)) }
    fun setMargin(margin: Float) = _state.update { it.copy(margin = margin.coerceIn(0f, 0.3f)) }

    // ------------------------------------------------------------- load/reset

    fun reset() = _state.update { EditorState() }

    fun loadForEdit(entity: ShortcutEntity, appLabel: String?) = _state.update { fromEntity(entity, appLabel) }

    fun loadAsCopy(entity: ShortcutEntity, appLabel: String?, copySuffix: String) = _state.update {
        fromEntity(entity, appLabel).copy(
            editingId = null, editingStatus = null, createdAt = null,
            name = (entity.name + copySuffix).take(BackupCodec.MAX_NAME),
        )
    }

    private fun fromEntity(e: ShortcutEntity, appLabel: String?) = EditorState(
        editingId = e.id,
        editingStatus = e.status,
        createdAt = e.createdAt,
        type = e.type,
        appPackage = if (e.type == TargetType.APP) e.target else null,
        appLabel = appLabel,
        url = if (e.type == TargetType.URL) e.target else "",
        name = e.name,
        nameEditedByUser = true,
        iconSource = e.iconSource,
        iconRef = e.iconRef,
        backgroundColor = e.backgroundColor,
        glyphColor = e.glyphColor,
        shape = e.shape,
        scale = e.scale,
        margin = e.margin,
    )

    // ----------------------------------------------------------------- submit

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (s.isEditing) saveEdit(s) else createAndPin(s)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun toEntity(s: EditorState, id: String, now: Long, status: ShortcutStatus) = ShortcutEntity(
        id = id,
        type = s.type,
        target = s.target!!,
        name = s.name.trim(),
        iconSource = s.iconSource!!,
        iconRef = s.iconRef!!,
        backgroundColor = s.backgroundColor,
        glyphColor = s.glyphColor,
        shape = s.shape,
        scale = s.scale,
        margin = s.margin,
        status = status,
        createdAt = s.createdAt ?: now,
        updatedAt = now,
    )

    private suspend fun createAndPin(s: EditorState) {
        if (!publisher.isPinSupported()) {
            _events.send(CreateEvent.PinUnsupported); return
        }
        val now = clock()
        val entity = toEntity(s, newId(), now, ShortcutStatus.ACTIF)
        // Saved before the request: if the user accepts, the launcher callback and the
        // resume-time sync find the row; if they cancel, the sync flags it NOT_PINNED.
        repo.save(entity)
        when (val r = publisher.requestPin(entity)) {
            PublishResult.Ok -> {
                onPinRequested()
                _events.send(CreateEvent.PinRequested)
                _state.value = EditorState()
            }
            PublishResult.PinUnsupported -> {
                repo.delete(entity); _events.send(CreateEvent.PinUnsupported)
            }
            PublishResult.RateLimited -> {
                repo.delete(entity); _events.send(CreateEvent.Error("rate limited"))
            }
            is PublishResult.Error -> {
                repo.delete(entity); _events.send(CreateEvent.Error(r.message))
            }
        }
    }

    private suspend fun saveEdit(s: EditorState) {
        val existing = repo.get(s.editingId!!) ?: run {
            _events.send(CreateEvent.Error("not found")); return
        }
        val now = clock()
        val updated = toEntity(s, existing.id, now, existing.status).copy(
            disabledReason = existing.disabledReason,
            createdAt = existing.createdAt,
        )
        if (existing.status != ShortcutStatus.ACTIF) {
            // Not on the home screen: nothing to push, the new look applies when pinned.
            repo.save(updated); _events.send(CreateEvent.Saved)
        } else when (publisher.update(listOf(updated))) {
            PublishResult.Ok -> {
                repo.save(updated.copy(pendingPush = false)); _events.send(CreateEvent.Saved)
            }
            else -> {
                // Keep the DB as the source of truth; ShortcutSync retries the push later.
                repo.save(updated.copy(pendingPush = true)); _events.send(CreateEvent.SavedPending)
            }
        }
        _state.value = EditorState()
    }

    companion object {
        fun hostLabel(url: String): String? = runCatching {
            val host = java.net.URI(url).host ?: return null
            host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        /** White or near-black glyph depending on background luminance (WCAG relative luminance). */
        fun contrastingGlyph(bg: Int): Int {
            fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
            val l = 0.2126 * ch((bg shr 16) and 0xFF) + 0.7152 * ch((bg shr 8) and 0xFF) + 0.0722 * ch(bg and 0xFF)
            return if (l > 0.45) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
        }
    }
}

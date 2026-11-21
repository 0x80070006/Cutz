package fr.cutz.shortcut

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import fr.cutz.data.db.ShortcutEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PublishResult {
    data object Ok : PublishResult
    /** The launcher cannot pin shortcuts (or the user disabled it). */
    data object PinUnsupported : PublishResult
    /** ShortcutManager refused because of its rate limiter; caller keeps pendingPush=true. */
    data object RateLimited : PublishResult
    data class Error(val message: String) : PublishResult
}

/** Thin, mockable facade over ShortcutManagerCompat (ViewModels are unit-tested against a fake). */
interface ShortcutPublisher {
    fun isPinSupported(): Boolean
    suspend fun requestPin(entity: ShortcutEntity): PublishResult
    suspend fun update(entities: List<ShortcutEntity>): PublishResult
    fun disable(ids: List<String>, message: String)
    fun enable(ids: List<String>)
    /** Pinned shortcut ids of this app, enabled or not. */
    fun pinnedIds(): Set<String>
}

class AndroidShortcutPublisher(
    private val context: Context,
    private val iconFactory: ShortcutIconFactory,
) : ShortcutPublisher {

    override fun isPinSupported() = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    override suspend fun requestPin(entity: ShortcutEntity): PublishResult {
        if (!isPinSupported()) return PublishResult.PinUnsupported
        return runCatching {
            val info = build(entity)
            // The callback fires only if the user accepts; it triggers a DB/home sync.
            val callback = PendingIntent.getBroadcast(
                context, entity.id.hashCode(),
                Intent(context, PinResultReceiver::class.java).putExtra(LaunchActivity.EXTRA_ID, entity.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (ShortcutManagerCompat.requestPinShortcut(context, info, callback.intentSender)) PublishResult.Ok
            else PublishResult.PinUnsupported
        }.getOrElse { PublishResult.Error(it.message ?: it.javaClass.simpleName) }
    }

    override suspend fun update(entities: List<ShortcutEntity>): PublishResult {
        if (entities.isEmpty()) return PublishResult.Ok
        // Rate limiting only applies while the app is in the background, but checking
        // first avoids a silent no-op: we report it and keep the change pending.
        if (ShortcutManagerCompat.isRateLimitingActive(context)) return PublishResult.RateLimited
        return runCatching {
            // Small chunks keep each Binder transaction well below 1 MB (icons are ARGB bitmaps).
            for (chunk in entities.chunked(4)) {
                val infos = chunk.map { build(it) }
                if (!ShortcutManagerCompat.updateShortcuts(context, infos)) return PublishResult.RateLimited
            }
            PublishResult.Ok
        }.getOrElse { PublishResult.Error(it.message ?: it.javaClass.simpleName) }
    }

    override fun disable(ids: List<String>, message: String) {
        if (ids.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, ids, message)
    }

    override fun enable(ids: List<String>) {
        // Platform API (minSdk 26 > 25): the compat variant needs full ShortcutInfo objects.
        if (ids.isNotEmpty()) context.getSystemService(ShortcutManager::class.java)?.enableShortcuts(ids)
    }

    override fun pinnedIds(): Set<String> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()

    private suspend fun build(entity: ShortcutEntity): ShortcutInfoCompat {
        val bitmap = withContext(Dispatchers.Default) { iconFactory.render(entity.iconSpec()) }
        return ShortcutInfoCompat.Builder(context, entity.id)
            .setShortLabel(entity.name)
            .setLongLabel(entity.name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setIntent(LaunchActivity.intent(context, entity.id))
            .build()
    }
}

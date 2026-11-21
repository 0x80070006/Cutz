package fr.cutz.data.backup

import android.content.Context
import android.net.Uri
import fr.cutz.data.GalleryStore
import fr.cutz.data.ShortcutRepository
import fr.cutz.data.db.DisabledReason
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import fr.cutz.util.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

data class DecodedShortcut(val entity: ShortcutEntity, val galleryPng: ByteArray?)

/**
 * JSON backup format (version 1). Gallery images are embedded as base64 PNG so a
 * backup is self-contained. Pure JVM code: unit-tested without Android.
 */
object BackupCodec {
    const val FORMAT = "cutz-backup"
    const val VERSION = 1

    fun encode(items: List<ShortcutEntity>, galleryBytes: (String) -> ByteArray?, now: Long): String {
        val arr = JSONArray()
        for (e in items) {
            val o = JSONObject()
                .put("id", e.id)
                .put("type", e.type.name)
                .put("target", e.target)
                .put("name", e.name)
                .put("iconSource", e.iconSource.name)
                .put("iconRef", e.iconRef)
                .put("backgroundColor", e.backgroundColor.toLong() and 0xFFFFFFFFL)
                .put("glyphColor", e.glyphColor.toLong() and 0xFFFFFFFFL)
                .put("shape", e.shape.name)
                .put("scale", e.scale.toDouble())
                .put("margin", e.margin.toDouble())
                .put("createdAt", e.createdAt)
                .put("updatedAt", e.updatedAt)
            if (e.iconSource == IconSourceType.GALLERY) {
                galleryBytes(e.iconRef)?.let { o.put("galleryPng", Base64.getEncoder().encodeToString(it)) }
            }
            arr.put(o)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("exportedAt", now)
            .put("shortcuts", arr).toString(2)
    }

    /**
     * Invalid entries are skipped rather than failing the whole import. Imported
     * shortcuts are not on this home screen yet, so they arrive as DESACTIVE/NOT_PINNED.
     */
    fun decode(json: String, now: Long): List<DecodedShortcut> {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "not a Cutz backup" }
        require(root.optInt("version") in 1..VERSION) { "unsupported backup version" }
        val arr = root.getJSONArray("shortcuts")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val type = TargetType.valueOf(o.getString("type"))
                val target = o.getString("target").trim()
                val validTarget = when (type) {
                    TargetType.URL -> UrlValidator.normalize(target)
                    TargetType.APP -> target.takeIf { PACKAGE.matches(it) }
                } ?: return@runCatching null
                val source = IconSourceType.valueOf(o.getString("iconSource"))
                val png = o.optString("galleryPng", "").takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
                if (source == IconSourceType.GALLERY && png == null) return@runCatching null
                DecodedShortcut(
                    ShortcutEntity(
                        id = o.getString("id"),
                        type = type,
                        target = validTarget,
                        name = o.getString("name").take(MAX_NAME),
                        iconSource = source,
                        iconRef = o.getString("iconRef"),
                        backgroundColor = o.getLong("backgroundColor").toInt(),
                        glyphColor = o.getLong("glyphColor").toInt(),
                        shape = runCatching { IconShape.valueOf(o.getString("shape")) }.getOrDefault(IconShape.SYSTEM),
                        scale = o.optDouble("scale", 0.6).toFloat().coerceIn(0.1f, 1f),
                        margin = o.optDouble("margin", 0.0).toFloat().coerceIn(0f, 0.4f),
                        status = ShortcutStatus.DESACTIVE,
                        disabledReason = DisabledReason.NOT_PINNED,
                        createdAt = o.optLong("createdAt", now),
                        updatedAt = now,
                    ),
                    png,
                )
            }.getOrNull()
        }
    }

    const val MAX_NAME = 40
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}

data class ImportReport(val imported: Int, val skipped: Int)

/** Backup port, so ViewModels do not depend on Android I/O in unit tests. */
interface BackupPort {
    suspend fun export(uri: Uri): Int
    suspend fun import(uri: Uri): ImportReport
}

/** Storage Access Framework I/O: the user picks the file, no storage permission needed. */
class BackupManager(
    private val context: Context,
    private val repo: ShortcutRepository,
    private val gallery: GalleryStore,
) : BackupPort {
    override suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val items = repo.getAll()
        val json = BackupCodec.encode(items, gallery::bytes, System.currentTimeMillis())
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
        items.size
    }

    override suspend fun import(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val decoded = BackupCodec.decode(text, System.currentTimeMillis())
        val existing = repo.getAll().map { it.id }.toSet()
        val fresh = decoded.filter { it.entity.id !in existing }
        val entities = fresh.map { d ->
            if (d.galleryPng != null) d.entity.copy(iconRef = gallery.saveBytes(d.galleryPng)) else d.entity
        }
        repo.saveAll(entities)
        ImportReport(imported = entities.size, skipped = decoded.size - entities.size)
    }
}

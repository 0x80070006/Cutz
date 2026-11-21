package fr.cutz.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TargetType { APP, URL }

enum class IconSourceType { BUNDLED, GALLERY, APP_ICON }

/**
 * SYSTEM = full-bleed adaptive icon: the launcher applies its own mask (circle on
 * a stock Pixel). The four other shapes are drawn by Cutz inside the 66 dp safe
 * zone of the adaptive bitmap, on a transparent background, so the launcher mask
 * never cuts them (see ShortcutIconFactory for the geometry).
 */
enum class IconShape { SYSTEM, CIRCLE, ROUNDED_SQUARE, SQUIRCLE, SQUARE }

enum class ShortcutStatus { ACTIF, DESACTIVE }

/** Why a shortcut is DESACTIVE. Null while ACTIF. */
enum class DisabledReason {
    /** The user deleted it: disabled on the home screen, waiting for manual removal. */
    DELETED_BY_USER,
    /** The target app was uninstalled. Re-enabled automatically if it comes back. */
    APP_UNINSTALLED,
    /** Not (or no longer) pinned: pin request cancelled, removed from home, or imported. */
    NOT_PINNED,
}

@Entity(tableName = "shortcuts")
data class ShortcutEntity(
    /** UUID, also used as the ShortcutInfo id. */
    @PrimaryKey val id: String,
    val type: TargetType,
    /** Package name (APP) or absolute http(s) URL (URL). */
    val target: String,
    val name: String,
    @ColumnInfo(name = "icon_source") val iconSource: IconSourceType,
    /** BUNDLED: icon id ("tabler/home"); GALLERY: file name in filesDir/gallery; APP_ICON: package. */
    @ColumnInfo(name = "icon_ref") val iconRef: String,
    /** ARGB colours stored as Int (Room has no unsigned type; ARGB fits in a signed Int). */
    @ColumnInfo(name = "background_color") val backgroundColor: Int,
    @ColumnInfo(name = "glyph_color") val glyphColor: Int,
    val shape: IconShape,
    /** Glyph size as a fraction of the shape (0.3..1.0). */
    val scale: Float,
    /** Extra inner margin as a fraction of the shape (0..0.3), applied after [scale]. */
    val margin: Float = 0f,
    val status: ShortcutStatus = ShortcutStatus.ACTIF,
    @ColumnInfo(name = "disabled_reason") val disabledReason: DisabledReason? = null,
    /** True when the DB changed but ShortcutManager could not be updated (rate limit). */
    @ColumnInfo(name = "pending_push") val pendingPush: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

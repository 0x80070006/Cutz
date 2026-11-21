package fr.cutz.data.icons

enum class IconPack(val key: String, val displayName: String) {
    SIMPLE_ICONS("simpleicons", "Simple Icons"),
    TABLER("tabler", "Tabler"),
    LUCIDE("lucide", "Lucide");

    companion object {
        fun fromKey(key: String): IconPack? = entries.firstOrNull { it.key == key }
    }
}

data class IconMeta(
    /** "<source>/<name>", stable across versions; stored in ShortcutEntity.iconRef. */
    val id: String,
    val pack: IconPack,
    val name: String,
    val title: String,
    val tags: List<String>,
    val category: IconCategory,
    /** Official brand colour (Simple Icons only), ARGB. */
    val brandColor: Int?,
) {
    val assetPath: String get() = "icons/${pack.key}/$name.svg"

    /** Pre-normalised text used by search: lowercase, accents stripped. */
    internal val haystack: String = IconIndex.normalize(buildString {
        append(name).append(' ').append(title)
        tags.forEach { append(' ').append(it) }
    })
}

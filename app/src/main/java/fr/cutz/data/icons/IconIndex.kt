package fr.cutz.data.icons

import org.json.JSONObject
import java.text.Normalizer

/**
 * In-memory index of the bundled icons (~12 000 entries). Pure Kotlin + org.json so it
 * is unit-testable on the JVM. A linear scan over pre-normalised strings takes a few
 * milliseconds, which is simpler and smaller than shipping a full-text engine.
 */
class IconIndex(val icons: List<IconMeta>) {

    private val byId = icons.associateBy { it.id }

    fun get(id: String): IconMeta? = byId[id]

    /**
     * @param query free text; every whitespace-separated term must appear in name, title or tags.
     * @param category null = all categories.
     * @param packs empty = all packs.
     */
    fun search(query: String, category: IconCategory? = null, packs: Set<IconPack> = emptySet()): List<IconMeta> {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() }
        val result = icons.asSequence()
            .filter { category == null || it.category == category }
            .filter { packs.isEmpty() || it.pack in packs }
            .filter { icon -> terms.all { icon.haystack.contains(it) } }
            .toList()
        if (terms.isEmpty()) return result
        // Rank exact / prefix name matches first so "home" shows the house before "smart-home-off".
        val first = terms.first().replace(' ', '-')
        return result.sortedBy {
            when {
                it.name == first -> 0
                it.name.startsWith(first) -> 1
                else -> 2
            }
        }
    }

    fun countByCategory(): Map<IconCategory, Int> = icons.groupingBy { it.category }.eachCount()

    companion object {
        private val DIACRITICS = "\\p{InCombiningDiacriticalMarks}+".toRegex()

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace('-', ' ')

        fun parse(json: String): IconIndex {
            val root = JSONObject(json)
            val arr = root.getJSONArray("icons")
            val list = ArrayList<IconMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pack = IconPack.fromKey(o.getString("source")) ?: continue
                val tagsArr = o.optJSONArray("tags")
                val tags = if (tagsArr == null) emptyList() else List(tagsArr.length()) { tagsArr.getString(it) }
                val color = o.optString("color", "").takeIf { it.length == 7 }
                    ?.let { (0xFF000000 or it.substring(1).toLong(16)).toInt() }
                list += IconMeta(
                    id = o.getString("id"),
                    pack = pack,
                    name = o.getString("name"),
                    title = o.optString("title", o.getString("name")),
                    tags = tags,
                    category = IconCategory.fromKey(o.optString("category")),
                    brandColor = color,
                )
            }
            return IconIndex(list)
        }
    }
}

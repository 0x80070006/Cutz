package fr.cutz.data.icons

import androidx.annotation.StringRes
import fr.cutz.R

/** Unified categories. [key] must match tools/mappings/categories.json (checked by tools/test_categorize.py). */
enum class IconCategory(val key: String, @StringRes val label: Int) {
    SOCIAL("social", R.string.cat_social),
    MEDIA("media", R.string.cat_media),
    COMMUNICATION("communication", R.string.cat_communication),
    PRODUCTIVITY("productivity", R.string.cat_productivity),
    DEVELOPMENT("development", R.string.cat_development),
    SECURITY("security", R.string.cat_security),
    CLOUD("cloud", R.string.cat_cloud),
    GAMES("games", R.string.cat_games),
    SHOPPING("shopping", R.string.cat_shopping),
    FINANCE("finance", R.string.cat_finance),
    TRAVEL("travel", R.string.cat_travel),
    HOME("home", R.string.cat_home),
    SYSTEM("system", R.string.cat_system),
    FILES("files", R.string.cat_files),
    NATURE("nature", R.string.cat_nature),
    HEALTH("health", R.string.cat_health),
    UI("ui", R.string.cat_ui),
    OTHER("other", R.string.cat_other);

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun fromKey(key: String?): IconCategory = byKey[key] ?: OTHER
    }
}

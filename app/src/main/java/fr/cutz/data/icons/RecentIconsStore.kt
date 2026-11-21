package fr.cutz.data.icons

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Récents" section of the picker. A tiny MRU list does not justify a Room table or a
 * DataStore dependency: SharedPreferences with a newline-joined string is enough.
 */
class RecentIconsStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_icons", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<String>> = _recent

    fun push(id: String) {
        val updated = (listOf(id) + _recent.value.filter { it != id }).take(MAX)
        prefs.edit().putString(KEY, updated.joinToString("\n")).apply()
        _recent.value = updated
    }

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        const val KEY = "ids"
        const val MAX = 24
    }
}

package fr.cutz.data.icons

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class IconPackInfo(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val repository: String,
    val count: Int,
)

/** Lazily loads assets/icons/index.json once (about 2 MB, parsed off the main thread). */
class IconCatalog(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var cached: IconIndex? = null

    suspend fun index(): IconIndex = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val json = context.assets.open("icons/index.json").bufferedReader().use { it.readText() }
            IconIndex.parse(json)
        }.also { cached = it }
    }

    suspend fun packs(): List<IconPackInfo> = withContext(Dispatchers.IO) {
        val arr = JSONArray(context.assets.open("icons/sources.json").bufferedReader().use { it.readText() })
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            IconPackInfo(
                o.getString("id"), o.getString("name"), o.getString("version"),
                o.getString("license"), o.getString("repository"), o.optInt("count"),
            )
        }
    }

    suspend fun licenseText(packId: String): String = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("licenses/$packId.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
}

package fr.cutz.data.apps

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/**
 * Launchable apps only. Visibility comes from the MAIN/LAUNCHER <queries> entry in the
 * manifest, so on Android 11+ apps without a launcher activity are invisible to us,
 * which is exactly the set a home-screen shortcut can target anyway.
 */
open class AppsRepository(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    open suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        infos.asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { InstalledApp(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    open fun isInstalled(packageName: String): Boolean = pm.getLaunchIntentForPackage(packageName) != null

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    fun loadIcon(packageName: String): Drawable? = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    fun label(packageName: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()
}

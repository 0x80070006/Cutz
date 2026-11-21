package fr.cutz

import android.app.Application
import android.content.Context
import fr.cutz.data.GalleryStore
import fr.cutz.data.RoomShortcutRepository
import fr.cutz.data.ShortcutRepository
import fr.cutz.data.apps.AppsRepository
import fr.cutz.data.backup.BackupManager
import fr.cutz.data.db.CutzDatabase
import fr.cutz.data.icons.IconCatalog
import fr.cutz.data.icons.RecentIconsStore
import fr.cutz.data.icons.SvgRenderer
import fr.cutz.shortcut.AndroidShortcutPublisher
import fr.cutz.shortcut.ShortcutIconFactory
import fr.cutz.shortcut.ShortcutPublisher
import fr.cutz.shortcut.ShortcutSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency injection. With about ten singletons, Hilt would add an annotation
 * processor, generated code and build time for no real gain; a plain container is
 * explicit and keeps the dependency list short (and F-Droid friendly).
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = CutzDatabase.build(context)
    val repository: ShortcutRepository = RoomShortcutRepository(database.shortcutDao())
    val catalog = IconCatalog(context)
    val svgRenderer = SvgRenderer(context)
    val recentIcons = RecentIconsStore(context)
    val apps = AppsRepository(context)
    val gallery = GalleryStore(context)
    val iconFactory = ShortcutIconFactory(context, svgRenderer, catalog, gallery, apps)
    val publisher: ShortcutPublisher = AndroidShortcutPublisher(context, iconFactory)
    val sync = ShortcutSync(
        repo = repository,
        publisher = publisher,
        isInstalled = apps::isInstalled,
        uninstalledMessage = { context.getString(R.string.disabled_app_uninstalled) },
    )
    val backup = BackupManager(context, repository, gallery)
}

class CutzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

package fr.cutz.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.cutz.CutzApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive (goAsync, < 10 s). */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** A targeted app was uninstalled: the sync disables its shortcuts with an explicit message. */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val app = context.applicationContext as CutzApp
        runAsync { app.container.sync.sync() }
    }
}

/** The user accepted the launcher pin dialog. */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CutzApp
        runAsync { app.container.sync.sync() }
    }
}

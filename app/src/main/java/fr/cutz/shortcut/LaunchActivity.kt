package fr.cutz.shortcut

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import fr.cutz.R
import fr.cutz.CutzApp
import fr.cutz.data.db.ShortcutStatus
import fr.cutz.data.db.TargetType
import kotlinx.coroutines.launch

/**
 * Trampoline behind every pinned shortcut.
 *
 * Why not put the target intent directly in the ShortcutInfo?
 *  1. Editing the target then needs no ShortcutManager call (no rate limit involved).
 *  2. An uninstalled app or a missing browser gives a clear message instead of the
 *     launcher's generic "App isn't installed".
 *  3. The exported activity only accepts an id, never a package or URL, so it cannot be
 *     abused by other apps to launch arbitrary intents with our identity.
 */
class LaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val container = (application as CutzApp).container
        lifecycleScope.launch {
            val entity = id?.let { container.repository.get(it) }
            when {
                entity == null -> toast(R.string.launch_not_found)
                entity.status == ShortcutStatus.DESACTIVE && entity.disabledReason != fr.cutz.data.db.DisabledReason.NOT_PINNED ->
                    toast(R.string.launch_disabled)
                entity.type == TargetType.APP -> {
                    val launch = container.apps.launchIntent(entity.target)
                    if (launch == null) toast(R.string.launch_app_missing)
                    else start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
                else -> start(
                    Intent(Intent.ACTION_VIEW, Uri.parse(entity.target))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            finish()
        }
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.launch_no_handler)
        } catch (_: SecurityException) {
            toast(R.string.launch_no_handler)
        }
    }

    private fun toast(res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION = "fr.cutz.action.LAUNCH_SHORTCUT"
        const val EXTRA_ID = "fr.cutz.extra.SHORTCUT_ID"

        fun intent(context: Context, id: String): Intent =
            Intent(ACTION).setClass(context, LaunchActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

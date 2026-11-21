package fr.cutz.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.cutz.BuildConfig
import fr.cutz.R
import fr.cutz.data.icons.IconPackInfo
import fr.cutz.ui.common.LocalContainer

/** Libraries compiled into the app (attribution for the code, not only the icons). */
private data class Library(val name: String, val license: String, val url: String)

private val LIBRARIES = listOf(
    Library("AndroidX (Compose, Material 3, Room, Lifecycle, Core)", "Apache-2.0", "https://github.com/androidx/androidx"),
    Library("Kotlin & kotlinx.coroutines", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    Library("AndroidSVG", "Apache-2.0", "https://github.com/BigBadaboom/androidsvg"),
)

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val packs by produceState<List<IconPackInfo>>(emptyList()) { value = container.catalog.packs() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_offline), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Text(stringResource(R.string.about_icons), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        items(packs, key = { it.id }) { PackCard(it) }
        item {
            Text(stringResource(R.string.about_trademarks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text(stringResource(R.string.about_libraries), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        items(LIBRARIES, key = { it.name }) { lib ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(lib.name, style = MaterialTheme.typography.titleSmall)
                    Text(lib.license, style = MaterialTheme.typography.bodySmall)
                    LinkButton(lib.url)
                }
            }
        }
    }
}

@Composable
private fun PackCard(p: IconPackInfo) {
    val container = LocalContainer.current
    var showLicense by remember { mutableStateOf(false) }
    val text by produceState("", p.id, showLicense) { if (showLicense) value = container.catalog.licenseText(p.id) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${p.name} ${p.version}", style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_pack_line, p.license, p.count), style = MaterialTheme.typography.bodySmall)
            LinkButton(p.repository)
            TextButton(onClick = { showLicense = !showLicense }) {
                Text(stringResource(if (showLicense) R.string.about_hide_license else R.string.about_show_license))
            }
            if (showLicense) Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Opens the repository in the user's browser; Cutz itself never goes online. */
@Composable
private fun LinkButton(url: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }, contentPadding = PaddingValues(0.dp)) { Text(url, style = MaterialTheme.typography.bodySmall) }
}

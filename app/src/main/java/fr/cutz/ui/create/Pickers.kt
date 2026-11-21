package fr.cutz.ui.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cutz.R
import fr.cutz.data.apps.InstalledApp
import fr.cutz.data.icons.IconCategory
import fr.cutz.data.icons.IconMeta
import fr.cutz.data.icons.IconPack
import fr.cutz.ui.common.IconThumb
import fr.cutz.ui.common.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ app picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    val container = LocalContainer.current
    val apps by produceState<List<InstalledApp>?>(null) { value = container.apps.launchableApps() }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(stringResource(R.string.app_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val filtered = remember(list, query) {
                list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        leadingContent = { AppIcon(app.packageName) },
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onPick(app) },
                    )
                }
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String) {
    val apps = LocalContainer.current.apps
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { apps.loadIcon(packageName)?.toBitmap(px, px) }
    }
    Box(Modifier.size(40.dp)) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp)) } }
}

// ----------------------------------------------------------------- icon picker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerDialog(vm: IconPickerViewModel, onDismiss: () -> Unit, onPick: (IconMeta) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.currentFilter.collectAsStateWithLifecycle()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.icon_bank)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                OutlinedTextField(
                    value = filter.query, onValueChange = vm::setQuery,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (filter.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) }
                    },
                    placeholder = { Text(stringResource(R.string.icon_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(CreateTags.ICON_SEARCH),
                )
                // Source filter.
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(IconPack.entries) { p ->
                        FilterChip(selected = p in filter.packs, onClick = { vm.togglePack(p) }, label = { Text(p.displayName) })
                    }
                }
                // Category chips.
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = filter.category == null, onClick = { vm.setCategory(null) }, label = { Text(stringResource(R.string.cat_all)) })
                    }
                    items(IconCategory.entries) { c ->
                        FilterChip(selected = filter.category == c, onClick = { vm.setCategory(if (filter.category == c) null else c) }, label = { Text(stringResource(c.label)) })
                    }
                }
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(64.dp),
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (state.showRecent) {
                            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.icon_recent)) }
                            items(state.recent, key = { "r-" + it.id }) { IconCell(it, onPick) }
                            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(stringResource(R.string.icon_all)) }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.icon_count, state.results.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                        items(state.results, key = { it.id }) { IconCell(it, onPick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))

@Composable
private fun IconCell(icon: IconMeta, onPick: (IconMeta) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(icon) }.padding(6.dp).testTag("icon_" + icon.id),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconThumb(icon, 32.dp)
        Text(
            icon.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Same name in several packs is kept: the source badge tells them apart.
        Row { Text(icon.pack.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1) }
    }
}

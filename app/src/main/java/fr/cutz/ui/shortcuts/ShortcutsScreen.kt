package fr.cutz.ui.shortcuts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cutz.R
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.db.TargetType
import fr.cutz.shortcut.iconSpec
import fr.cutz.ui.common.ShortcutIcon

private sealed interface Confirm {
    data class Delete(val e: ShortcutEntity) : Confirm
    data class Purge(val e: ShortcutEntity) : Confirm
}

@Composable
fun ShortcutsScreen(
    vm: ShortcutsViewModel,
    onEdit: (ShortcutEntity) -> Unit,
    onDuplicate: (ShortcutEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    when {
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.isEmpty -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.list_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.toRemove.isNotEmpty()) {
                item(key = "h-remove") { RemovalHeader() }
                items(state.toRemove, key = { "x-" + it.entity.id }) { p ->
                    ShortcutRow(
                        p.entity, greyed = true,
                        status = stringResource(if (p.stillOnHome) R.string.status_to_remove else R.string.status_removed),
                        statusIcon = if (p.stillOnHome) Icons.Default.TouchApp else Icons.Default.CheckCircle,
                    ) {
                        if (p.stillOnHome) {
                            TextButton(onClick = { vm.restore(p.entity) }) { Text(stringResource(R.string.action_restore)) }
                        }
                        TextButton(onClick = { confirm = Confirm.Purge(p.entity) }) {
                            Text(stringResource(if (p.stillOnHome) R.string.action_confirm_removed else R.string.action_purge))
                        }
                    }
                }
            }
            section(R.string.section_active, state.active) { e ->
                ShortcutRow(e, greyed = false, status = stringResource(R.string.status_active)) {
                    RowMenu(
                        onEdit = { onEdit(e) }, onDuplicate = { onDuplicate(e) }, onDelete = { confirm = Confirm.Delete(e) },
                    )
                }
            }
            section(R.string.section_not_pinned, state.notPinned) { e ->
                ShortcutRow(e, greyed = true, status = stringResource(R.string.status_not_pinned)) {
                    IconButton(onClick = { vm.pin(e) }) { Icon(Icons.Default.PushPin, stringResource(R.string.action_pin)) }
                    RowMenu(onEdit = { onEdit(e) }, onDuplicate = { onDuplicate(e) }, onDelete = { confirm = Confirm.Delete(e) })
                }
            }
            section(R.string.section_app_missing, state.appMissing) { e ->
                ShortcutRow(e, greyed = true, status = stringResource(R.string.status_app_missing), statusIcon = Icons.Default.Warning) {
                    RowMenu(onEdit = null, onDuplicate = { onDuplicate(e) }, onDelete = { confirm = Confirm.Delete(e) })
                }
            }
        }
    }

    when (val c = confirm) {
        is Confirm.Delete -> AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.confirm_delete_title, c.e.name)) },
            text = { Text(stringResource(R.string.confirm_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.delete(c.e); confirm = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is Confirm.Purge -> AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text(stringResource(R.string.confirm_purge_title)) },
            text = { Text(stringResource(R.string.confirm_purge_body, c.e.name)) },
            confirmButton = { TextButton(onClick = { vm.purge(c.e); confirm = null }) { Text(stringResource(R.string.action_purge)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

private fun LazyListScope.section(title: Int, list: List<ShortcutEntity>, row: @Composable (ShortcutEntity) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$title") {
        Text(
            stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(list, key = { "$title-" + it.id }) { row(it) }
}

@Composable
private fun RemovalHeader() {
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.section_to_remove), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.to_remove_instructions), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ShortcutRow(
    e: ShortcutEntity,
    greyed: Boolean,
    status: String,
    statusIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actions: @Composable () -> Unit,
) {
    ListItem(
        leadingContent = { ShortcutIcon(e.iconSpec(), key = e.updatedAt, size = 48.dp, greyed = greyed) },
        headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    (if (e.type == TargetType.APP) "▣ " else "↗ ") + e.target,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(status) }, leadingIcon = statusIcon?.let { { Icon(it, null) } })
                }
            }
        },
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { actions() } },
    )
}

@Composable
private fun RowMenu(onEdit: (() -> Unit)?, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onEdit != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { open = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_duplicate)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = { open = false; onDuplicate() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

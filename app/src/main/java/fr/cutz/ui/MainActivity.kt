package fr.cutz.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.cutz.AppContainer
import fr.cutz.R
import fr.cutz.CutzApp
import fr.cutz.data.db.TargetType
import fr.cutz.ui.about.AboutScreen
import fr.cutz.ui.common.LocalContainer
import fr.cutz.ui.create.CreateEvent
import fr.cutz.ui.create.CreateScreen
import fr.cutz.ui.create.CreateViewModel
import fr.cutz.ui.create.IconPickerViewModel
import fr.cutz.ui.shortcuts.ShortcutsEvent
import fr.cutz.ui.shortcuts.ShortcutsScreen
import fr.cutz.ui.shortcuts.ShortcutsViewModel
import fr.cutz.ui.theme.CutzTheme
import kotlinx.coroutines.launch

private enum class Screen { CREATE, LIST, ABOUT }

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as CutzApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                CutzTheme { CutzRoot(container) }
            }
        }
    }

    /** Every return to the app re-checks the home screen (icon removed by hand, pin dialog answered...). */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { container.sync.sync() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CutzRoot(c: AppContainer) {
    val context = LocalContext.current
    val createVm: CreateViewModel = viewModel(factory = viewModelFactory {
        initializer {
            CreateViewModel(
                repo = c.repository,
                publisher = c.publisher,
                renderPreview = { spec -> c.iconFactory.render(spec) },
                onIconUsed = c.recentIcons::push,
                onPinRequested = { c.sync.syncSoon(c.appScope) },
                saveGalleryImage = c.gallery::save,
            )
        }
    })
    val pickerVm: IconPickerViewModel = viewModel(factory = viewModelFactory {
        initializer { IconPickerViewModel(c.catalog, c.recentIcons.recent) }
    })
    val deletedMessage = stringResource(R.string.disabled_deleted)
    val listVm: ShortcutsViewModel = viewModel(factory = viewModelFactory {
        initializer {
            ShortcutsViewModel(
                repo = c.repository,
                publisher = c.publisher,
                pinnedIds = c.sync.pinnedIds,
                refreshPinned = { c.sync.sync() },
                deletedMessage = deletedMessage,
                deleteGalleryFile = c.gallery::delete,
                backup = c.backup,
                onPinRequested = { c.sync.syncSoon(c.appScope) },
            )
        }
    })

    var screen by rememberSaveable { mutableStateOf(Screen.CREATE) }
    var pinUnsupported by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(listVm::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(listVm::import)
    }

    LaunchedEffect(Unit) {
        createVm.events.collect { e ->
            when (e) {
                CreateEvent.PinRequested -> snackbar.showSnackbar(context.getString(R.string.msg_pin_requested))
                CreateEvent.PinUnsupported -> pinUnsupported = true
                CreateEvent.Saved -> { snackbar.showSnackbar(context.getString(R.string.msg_saved)); screen = Screen.LIST }
                CreateEvent.SavedPending -> { snackbar.showSnackbar(context.getString(R.string.msg_saved_pending)); screen = Screen.LIST }
                is CreateEvent.Error -> snackbar.showSnackbar(context.getString(R.string.msg_error, e.message))
            }
        }
    }
    LaunchedEffect(Unit) {
        listVm.events.collect { e ->
            val msg = when (e) {
                is ShortcutsEvent.Disabled -> context.getString(R.string.msg_disabled, e.name)
                is ShortcutsEvent.Purged -> context.getString(R.string.msg_purged, e.name)
                ShortcutsEvent.PinRequested -> context.getString(R.string.msg_pin_requested)
                ShortcutsEvent.PinUnsupported -> { pinUnsupported = true; null }
                is ShortcutsEvent.Exported -> context.getString(R.string.msg_exported, e.count)
                is ShortcutsEvent.Imported -> context.getString(R.string.msg_imported, e.report.imported, e.report.skipped)
                is ShortcutsEvent.Error -> context.getString(R.string.msg_error, e.message)
            }
            msg?.let { snackbar.showSnackbar(it) }
        }
    }

    BackHandler(enabled = screen != Screen.CREATE) { screen = if (screen == Screen.ABOUT) Screen.LIST else Screen.CREATE }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(when (screen) {
                        Screen.CREATE -> R.string.tab_create
                        Screen.LIST -> R.string.tab_list
                        Screen.ABOUT -> R.string.about_title
                    }))
                },
                navigationIcon = {
                    if (screen == Screen.ABOUT) IconButton(onClick = { screen = Screen.LIST }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (screen != Screen.ABOUT) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export)) }, leadingIcon = { Icon(Icons.Default.Upload, null) },
                                onClick = { menuOpen = false; exportLauncher.launch("cutz-shortcuts.json") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_import)) }, leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_title)) }, leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { menuOpen = false; screen = Screen.ABOUT },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (screen != Screen.ABOUT) NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.CREATE, onClick = { screen = Screen.CREATE },
                    icon = { Icon(Icons.Default.AddCircle, null) }, label = { Text(stringResource(R.string.tab_create)) },
                )
                NavigationBarItem(
                    selected = screen == Screen.LIST, onClick = { screen = Screen.LIST; listVm.refresh() },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text(stringResource(R.string.tab_list)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (screen) {
            Screen.CREATE -> CreateScreen(createVm, pickerVm, m)
            Screen.LIST -> ShortcutsScreen(
                listVm,
                onEdit = { e -> createVm.loadForEdit(e, appLabel(c, e.type, e.target)); screen = Screen.CREATE },
                onDuplicate = { e ->
                    createVm.loadAsCopy(e, appLabel(c, e.type, e.target), context.getString(R.string.copy_suffix))
                    screen = Screen.CREATE
                },
                modifier = m,
            )
            Screen.ABOUT -> AboutScreen(m)
        }
    }

    if (pinUnsupported) {
        AlertDialog(
            onDismissRequest = { pinUnsupported = false },
            title = { Text(stringResource(R.string.pin_unsupported_title)) },
            text = { Text(stringResource(R.string.pin_unsupported_body)) },
            confirmButton = { TextButton(onClick = { pinUnsupported = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun appLabel(c: AppContainer, type: TargetType, target: String) = if (type == TargetType.APP) c.apps.label(target) else null

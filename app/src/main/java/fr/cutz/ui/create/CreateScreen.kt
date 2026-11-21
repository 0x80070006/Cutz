package fr.cutz.ui.create

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.cutz.R
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.TargetType
import fr.cutz.ui.common.AdaptivePreview
import fr.cutz.ui.common.ColorRow

object CreateTags {
    const val TYPE_URL = "type_url"
    const val URL_FIELD = "url_field"
    const val NAME_FIELD = "name_field"
    const val ICON_BANK = "icon_bank"
    const val ICON_SEARCH = "icon_search"
    const val SUBMIT = "submit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(
    vm: CreateViewModel,
    pickerVm: IconPickerViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var showIconPicker by rememberSaveable { mutableStateOf(false) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }

    // Photo Picker: no storage permission; on GrapheneOS it is the system picker
    // (Android 13+) or falls back to ACTION_OPEN_DOCUMENT on older releases.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> cropUri = uri }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // -------------------------------------------------------------- preview
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AdaptivePreview(preview, 96.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.name.ifBlank { stringResource(R.string.preview_placeholder) },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (s.isEditing) Text(stringResource(R.string.editing_banner), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        // --------------------------------------------------------------- target
        SectionTitle(stringResource(R.string.section_target))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = s.type == TargetType.APP, onClick = { vm.setType(TargetType.APP) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Default.Apps, null) },
            ) { Text(stringResource(R.string.type_app)) }
            SegmentedButton(
                selected = s.type == TargetType.URL, onClick = { vm.setType(TargetType.URL) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Default.Link, null) },
                modifier = Modifier.testTag(CreateTags.TYPE_URL),
            ) { Text(stringResource(R.string.type_url)) }
        }
        if (s.type == TargetType.APP) {
            OutlinedCard(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    s.appPackage?.let { AppIcon(it); Spacer(Modifier.width(12.dp)) }
                    Column {
                        Text(s.appLabel ?: s.appPackage ?: stringResource(R.string.app_choose), style = MaterialTheme.typography.titleMedium)
                        s.appPackage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = s.url, onValueChange = vm::setUrl,
                label = { Text(stringResource(R.string.url_label)) },
                placeholder = { Text("https://example.org") },
                isError = s.showUrlError,
                supportingText = { if (s.showUrlError) Text(stringResource(R.string.url_invalid)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(CreateTags.URL_FIELD),
            )
        }

        // ----------------------------------------------------------------- name
        OutlinedTextField(
            value = s.name, onValueChange = vm::setName,
            label = { Text(stringResource(R.string.name_label)) },
            supportingText = { Text("${s.name.length} / 40") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(CreateTags.NAME_FIELD),
        )

        // ----------------------------------------------------------------- icon
        SectionTitle(stringResource(R.string.section_icon))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.iconSource == IconSourceType.BUNDLED, onClick = { showIconPicker = true },
                label = { Text(s.iconTitle?.takeIf { s.iconSource == IconSourceType.BUNDLED } ?: stringResource(R.string.icon_bank)) },
                leadingIcon = { Icon(Icons.Default.Category, null) },
                modifier = Modifier.testTag(CreateTags.ICON_BANK),
            )
            FilterChip(
                selected = s.iconSource == IconSourceType.GALLERY,
                onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                label = { Text(stringResource(R.string.icon_gallery)) },
                leadingIcon = { Icon(Icons.Default.Image, null) },
            )
            if (s.type == TargetType.APP && s.appPackage != null) {
                FilterChip(
                    selected = s.iconSource == IconSourceType.APP_ICON, onClick = vm::useAppIcon,
                    label = { Text(stringResource(R.string.icon_app)) },
                    leadingIcon = { Icon(Icons.Default.Apps, null) },
                )
            }
        }

        // ---------------------------------------------------------- appearance
        SectionTitle(stringResource(R.string.section_style))
        Text(stringResource(R.string.shape_label), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(IconShape.entries) { shape ->
                FilterChip(selected = s.shape == shape, onClick = { vm.setShape(shape) }, label = { Text(stringResource(shape.label())) })
            }
        }
        if (s.shape == IconShape.SYSTEM) {
            Text(stringResource(R.string.shape_system_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.background_color), style = MaterialTheme.typography.labelLarge)
        ColorRow(s.backgroundColor, vm::setBackground)
        if (s.iconSource == IconSourceType.BUNDLED) {
            Text(stringResource(R.string.glyph_color), style = MaterialTheme.typography.labelLarge)
            ColorRow(s.glyphColor, vm::setGlyphColor)
        }
        Text(stringResource(R.string.glyph_scale, (s.scale * 100).toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(s.scale, vm::setScale, valueRange = 0.3f..1f)
        Text(stringResource(R.string.glyph_margin, (s.margin * 100).toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(s.margin, vm::setMargin, valueRange = 0f..0.3f)

        // --------------------------------------------------------------- submit
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (s.isEditing) OutlinedButton(onClick = vm::reset) { Text(stringResource(R.string.cancel)) }
            Button(onClick = vm::submit, enabled = s.canSubmit, modifier = Modifier.testTag(CreateTags.SUBMIT)) {
                Icon(if (s.isEditing) Icons.Default.Save else Icons.Default.PushPin, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (s.isEditing) R.string.save_changes else R.string.add_to_home))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAppPicker) AppPickerSheet(onDismiss = { showAppPicker = false }) { vm.selectApp(it.packageName, it.label); showAppPicker = false }
    if (showIconPicker) IconPickerDialog(pickerVm, onDismiss = { showIconPicker = false }) { vm.selectBundledIcon(it); showIconPicker = false }
    cropUri?.let { uri -> CropDialog(uri, onDismiss = { cropUri = null }) { vm.setGalleryImage(it); cropUri = null } }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

fun IconShape.label(): Int = when (this) {
    IconShape.SYSTEM -> R.string.shape_system
    IconShape.CIRCLE -> R.string.shape_circle
    IconShape.ROUNDED_SQUARE -> R.string.shape_rounded
    IconShape.SQUIRCLE -> R.string.shape_squircle
    IconShape.SQUARE -> R.string.shape_square
}

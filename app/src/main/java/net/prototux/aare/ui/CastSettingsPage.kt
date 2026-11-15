package net.prototux.aare.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.cast.CastController
import net.prototux.aare.cast.CastDisplayPlan
import net.prototux.aare.cast.CastSetup

/** The cast's settings: the root setup, and one switch per thing the cast does. */
@Composable
internal fun CastSettings(settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(settings.castRootReady) }
    var asking by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(settings.castTouch) }
    var blank by remember { mutableStateOf(settings.castBlankScreen) }
    var panelOff by remember { mutableStateOf(settings.castPanelOff) }
    var autoLock by remember { mutableStateOf(settings.castAutoLock) }
    var autoUnlock by remember { mutableStateOf(settings.castAutoUnlock) }
    // Never shown back: the field starts empty, and only says whether a code is saved.
    var codeSaved by remember { mutableStateOf(settings.castUnlockCode.isNotEmpty()) }
    var code by remember { mutableStateOf("") }
    var hotspot by remember { mutableStateOf(settings.castHotspot) }
    var audio by remember { mutableStateOf(settings.castAudio) }
    var landscape by remember { mutableStateOf(settings.castLandscape) }
    var resize by remember { mutableStateOf(settings.castResize) }
    var resolution by remember { mutableStateOf(settings.castResolution) }
    var customWidth by remember { mutableStateOf(settings.castCustomWidth.takeIf { it > 0 }?.toString() ?: "") }
    var customHeight by remember { mutableStateOf(settings.castCustomHeight.takeIf { it > 0 }?.toString() ?: "") }
    var dpi by remember { mutableStateOf(settings.castDpi.takeIf { it > 0 }?.toString() ?: "") }
    var uiScale by remember { mutableStateOf(settings.castUiScale.toString()) }
    var fontScale by remember { mutableStateOf(settings.castFontScale.toString()) }

    // Asks the root manager (its prompt, once), then lets root grant the rest.
    val setUp: () -> Unit = {
        if (!asking) {
            asking = true
            denied = false
            scope.launch {
                val result = withContext(Dispatchers.IO) { CastSetup.run(ctx.applicationContext, settings) }
                root = result.root
                denied = !result.root
                asking = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.cast_section_mode)) {
            Text(stringResource(R.string.cast_mode_text), style = MaterialTheme.typography.bodyMedium)
            Check(
                stringResource(R.string.cast_root_title),
                stringResource(when {
                    root -> R.string.cast_root_ready
                    asking -> R.string.cast_root_checking
                    denied -> R.string.cast_root_denied
                    else -> R.string.cast_root_missing
                }),
                root,
                setUp,
            )
            Hint(stringResource(R.string.cast_applies_next_connection))
        }

        Section(stringResource(R.string.cast_section_behaviour)) {
            Toggle(stringResource(R.string.cast_toggle_touch_title), stringResource(R.string.cast_toggle_touch_subtitle), touch) {
                touch = it; settings.castTouch = it
            }
            Toggle(stringResource(R.string.cast_toggle_panel_title), stringResource(R.string.cast_toggle_panel_subtitle), panelOff) {
                panelOff = it; settings.castPanelOff = it
            }
            Toggle(stringResource(R.string.cast_toggle_blank_title), stringResource(R.string.cast_toggle_blank_subtitle), blank) {
                blank = it; settings.castBlankScreen = it
            }
            Toggle(stringResource(R.string.cast_toggle_audio_title), stringResource(R.string.cast_toggle_audio_subtitle), audio) {
                audio = it; settings.castAudio = it
            }
            if (audio && !CastSetup.audioSupported) Hint(stringResource(R.string.cast_audio_needs_android12))
            Toggle(stringResource(R.string.cast_toggle_landscape_title), stringResource(R.string.cast_toggle_landscape_subtitle), landscape) {
                landscape = it; settings.castLandscape = it
            }
        }

        Section(stringResource(R.string.cast_section_car)) {
            Toggle(stringResource(R.string.cast_toggle_lock_title), stringResource(R.string.cast_toggle_lock_subtitle), autoLock) {
                autoLock = it; settings.castAutoLock = it
            }
            Toggle(stringResource(R.string.cast_toggle_unlock_title), stringResource(R.string.cast_toggle_unlock_subtitle), autoUnlock) {
                autoUnlock = it; settings.castAutoUnlock = it
            }
            if (autoUnlock) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(64) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    label = { Text(stringResource(if (codeSaved) R.string.cast_unlock_code_saved else R.string.cast_unlock_code_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = code.isNotEmpty(), onClick = {
                        settings.castUnlockCode = code
                        codeSaved = settings.castUnlockCode.isNotEmpty()
                        code = ""
                    }) { Text(stringResource(R.string.cast_unlock_code_save)) }
                    TextButton(enabled = codeSaved, onClick = {
                        settings.castUnlockCode = ""
                        codeSaved = false
                        code = ""
                    }) { Text(stringResource(R.string.cast_unlock_code_forget)) }
                }
                Hint(stringResource(R.string.cast_unlock_code_hint))
            }
            Toggle(stringResource(R.string.cast_toggle_hotspot_title), stringResource(R.string.cast_toggle_hotspot_subtitle), hotspot) {
                hotspot = it; settings.castHotspot = it
            }
            Hint(stringResource(R.string.cast_car_gps_note))
        }

        Section(stringResource(R.string.cast_section_display)) {
            Toggle(stringResource(R.string.cast_toggle_resize_title), stringResource(R.string.cast_toggle_resize_subtitle), resize) {
                resize = it; settings.castResize = it
            }
            if (resize) {
                Spacer(Modifier.height(8.dp))
                Choices(
                    stringResource(R.string.cast_resolution_title),
                    listOf(
                        CastDisplayPlan.RES_HEAD_UNIT to stringResource(R.string.cast_resolution_head_unit),
                        CastDisplayPlan.RES_DEVICE to stringResource(R.string.cast_resolution_device),
                        CastDisplayPlan.RES_CUSTOM to stringResource(R.string.cast_resolution_custom),
                    ),
                    resolution,
                ) { resolution = it; settings.castResolution = it }
                Hint(stringResource(when (resolution) {
                    CastDisplayPlan.RES_DEVICE -> R.string.cast_resolution_device_hint
                    CastDisplayPlan.RES_CUSTOM -> R.string.cast_resolution_custom_hint
                    else -> R.string.cast_resolution_head_unit_hint
                }))
                if (resolution == CastDisplayPlan.RES_CUSTOM) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(stringResource(R.string.cast_custom_width), customWidth, Modifier.weight(1f)) {
                            customWidth = it; settings.castCustomWidth = it.toIntOrNull() ?: 0
                        }
                        NumberField(stringResource(R.string.cast_custom_height), customHeight, Modifier.weight(1f)) {
                            customHeight = it; settings.castCustomHeight = it.toIntOrNull() ?: 0
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.cast_ui_scale_title), SCALES.map { it.toString() to "$it%" }, uiScale) {
                uiScale = it; settings.castUiScale = it.toIntOrNull() ?: 100
            }
            Hint(stringResource(R.string.cast_ui_scale_hint))
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.cast_font_scale_title), SCALES.map { it.toString() to "$it%" }, fontScale) {
                fontScale = it; settings.castFontScale = it.toIntOrNull() ?: 100
            }
            Hint(stringResource(R.string.cast_font_scale_hint))
            NumberField(stringResource(R.string.cast_dpi_label), dpi, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                dpi = it; settings.castDpi = it.toIntOrNull() ?: 0
            }
            Hint(stringResource(R.string.cast_dpi_hint))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { CastController.restoreNow(ctx.applicationContext) }
                    Toast.makeText(ctx, if (ok) R.string.cast_restore_done else R.string.cast_restore_failed, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.cast_restore_button)) }
            Hint(stringResource(R.string.cast_restore_hint))
        }
    }
}

/** The sizes offered, in percent of the phone's own: Android's settings stop around 85. */
private val SCALES = listOf(100, 85, 75, 65, 55)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/** A field that only takes digits; empty means "not set". */
@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

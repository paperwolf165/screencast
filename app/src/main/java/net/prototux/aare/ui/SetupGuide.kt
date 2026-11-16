package net.prototux.aare.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.cast.CastSetup
import net.prototux.aare.mirror.MirrorTouchService
import net.prototux.aare.service.ProjectionService

/**
 * The home screen's guide. First one question, rooted phone or not, with what each way gives;
 * then only that way's steps, each done right here with a tap, and a line saying when the phone is
 * ready for the car. [openSettings] opens the Screen cast page, for everything else.
 */
@Composable
internal fun SetupGuide(settings: Settings, state: ProjectionService.State, refresh: Int, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(settings.castModeChosen) }
    var lite by remember { mutableStateOf(settings.castLite) }
    // Read again whenever the user comes back to the app (refresh), and after each step here.
    var root by remember(refresh) { mutableStateOf(settings.castRootReady) }
    var androidAuto by remember(refresh) { mutableStateOf(CastSetup.androidAutoEnabled(ctx)) }
    var asking by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    val shareConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) ProjectionService.giveAudioConsent(ctx, r.resultCode, r.data!!)
    }
    val sharePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        shareConsent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent())
    }

    fun choose(withoutRoot: Boolean) {
        lite = withoutRoot
        settings.castLite = withoutRoot
        settings.castModeChosen = true
        chosen = true
    }

    if (!chosen) {
        Section(stringResource(R.string.guide_choose_title)) {
            Text(stringResource(R.string.guide_choose_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ModeOption(stringResource(R.string.guide_root_title), stringResource(R.string.guide_root_text)) { choose(withoutRoot = false) }
            Spacer(Modifier.height(10.dp))
            ModeOption(stringResource(R.string.guide_lite_title), stringResource(R.string.guide_lite_text)) { choose(withoutRoot = true) }
        }
        return
    }

    Section(stringResource(if (lite) R.string.guide_steps_title_lite else R.string.guide_steps_title_root)) {
        val ready: Boolean
        if (lite) {
            if (androidAuto) Check(stringResource(R.string.cast_android_auto_title), stringResource(R.string.cast_android_auto_enabled_lite), false) {
                runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", CastSetup.ANDROID_AUTO, null)))
                }
            }
            Check(
                stringResource(R.string.cast_share_title),
                stringResource(if (state.audioCapture) R.string.cast_share_ready else R.string.cast_share_missing),
                state.audioCapture,
            ) {
                sharePermissions.launch(buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray())
            }
            if (settings.castTouch) {
                val service = MirrorTouchService.instance != null
                Check(
                    stringResource(R.string.guide_touch_title),
                    stringResource(if (service) R.string.cast_touch_service_on else R.string.cast_touch_service_off),
                    service,
                ) { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            }
            ready = !androidAuto && state.audioCapture
        } else {
            Check(
                stringResource(R.string.cast_root_title),
                stringResource(when {
                    root -> R.string.cast_root_ready
                    asking -> R.string.cast_root_checking
                    denied -> R.string.cast_root_denied
                    else -> R.string.cast_root_missing
                }),
                root,
            ) {
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
            if (androidAuto) Check(stringResource(R.string.cast_android_auto_title), stringResource(R.string.cast_android_auto_enabled), false) {
                scope.launch {
                    withContext(Dispatchers.IO) { CastSetup.setAndroidAuto(ctx.applicationContext, enabled = false) }
                    androidAuto = CastSetup.androidAutoEnabled(ctx)
                    // Turned off here: it stays off.
                    if (!androidAuto) settings.castKeepAndroidAutoOff = true
                }
            }
            ready = root && !androidAuto
        }
        if (ready) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(if (lite) R.string.guide_ready_lite else R.string.guide_ready_root),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = openSettings) { Text(stringResource(R.string.guide_all_settings)) }
            TextButton(onClick = { chosen = false }) {
                Text(stringResource(if (lite) R.string.guide_switch_to_root else R.string.guide_switch_to_lite))
            }
        }
    }
}

/** One of the two ways to cast, as a large row to tap. */
@Composable
private fun ModeOption(title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

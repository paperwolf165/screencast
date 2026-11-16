package net.prototux.aare.ui

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.cast.RootShell

/**
 * What the app's own log can't see when a connection won't hold: why Android ended the app's
 * process, what the phone's USB stack did, and the kernel's side of it. Collected with root into
 * one file in Download, to be read on a computer.
 */
internal object Diagnostics {
    const val FILE = "/sdcard/Download/wingmirror-diagnostics.txt"

    private const val LOG_FILTER = "usb|accessory|aare|net.prototux|ActivityManager|am_kill|am_proc_died|lowmemorykiller|lmkd|" +
        "AndroidRuntime|FATAL|tombstone|DEBUG|libc|audio_hw|AudioPolicy|AudioFlinger|SurfaceFlinger|DisplayManager|VirtualDisplay|" +
        "MediaCodec|tether"
    private const val KERNEL_FILTER = "usb|gadget|configfs|android_work|dwc|accessory|f_acc|typec|pd_|lowmem|oom|killed|vbus"

    /** Everything is read, nothing is changed. [pkg] is this app's package. */
    private fun script(pkg: String) = """
        out=$FILE
        {
        echo "== when"; date
        echo "== device"; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.display.id; getprop ro.lineage.version
        echo "== app"; dumpsys package $pkg | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" | head -8
        echo "== why the app's process ended, most recent first"; dumpsys activity exit-info $pkg | head -220
        echo "== usb state"; dumpsys usb | head -220
        echo "== crash buffer"; logcat -d -b crash -t 400
        echo "== logcat"; logcat -d -b main,system,events -t 12000 | grep -i -E "$LOG_FILTER" | tail -3500
        echo "== kernel"; dmesg | grep -i -E "$KERNEL_FILTER" | tail -400
        } > ${'$'}out 2>&1
        chmod 664 ${'$'}out
        echo saved
    """.trimIndent()

    /** Blocks for a few seconds. True if the file was written. */
    fun save(pkg: String): Boolean = RootShell.run(script(pkg), timeoutSeconds = 60)?.contains("saved") == true
}

@Composable
internal fun DiagnosticsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Section(stringResource(R.string.diag_title)) {
        Text(stringResource(R.string.diag_text, Diagnostics.FILE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Diagnostics.save(ctx.packageName) }
                busy = false
                Toast.makeText(ctx, if (ok) R.string.diag_saved else R.string.diag_failed, Toast.LENGTH_LONG).show()
            }
        }) { Text(stringResource(if (busy) R.string.diag_saving else R.string.diag_button)) }
    }
}

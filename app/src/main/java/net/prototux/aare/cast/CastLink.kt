package net.prototux.aare.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import net.prototux.aare.BuildConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One-off root shell commands, for setup and for restoring the display when no helper runs. */
object RootShell {
    /** Runs [script] with `su -c`; what it printed, or null if su couldn't be started. Blocks: not for the main thread. */
    fun run(script: String, timeoutSeconds: Long = 20): String? = runCatching {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) p.destroy()
        out
    }.onFailure { Log.i("AareRootShell", "su: $it") }.getOrNull()

    /** Whether su gives root. The first call shows the root manager's prompt (Magisk, KernelSU, APatch). */
    fun granted(): Boolean = run("id")?.contains("uid=0") == true
}

/**
 * App-side connection to the root [CastHostService]: starts [CastMain] with su and waits for the
 * binder it broadcasts back. [connect] blocks and must run off the main thread; the other calls
 * are quick binder calls and never throw.
 */
class CastLink(private val context: Context) {
    @Volatile private var host: ICastHost? = null
    private var process: java.lang.Process? = null
    /** The helper watches this binder: when the app's process dies, it restores the display and exits. */
    private val client = Binder()

    val alive: Boolean get() = host?.asBinder()?.isBinderAlive == true

    fun connect(): Boolean {
        val token = UUID.randomUUID().toString()
        val got = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val bundle = intent?.getBundleExtra(CastMain.EXTRA_BUNDLE) ?: return
                // Exported, as root is another uid: only the helper started here knows the token.
                if (bundle.getString(CastMain.EXTRA_TOKEN) != token) return
                host = bundle.getBinder(CastMain.EXTRA_BINDER)?.let(ICastHost.Stub::asInterface)
                got.countDown()
            }
        }
        val filter = IntentFilter(CastMain.ACTION_HOST)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        try {
            val apk = context.applicationInfo.sourceDir
            val cmd = "${CastMain.ENV_TOKEN}=$token CLASSPATH='$apk' app_process / ${CastMain::class.java.name} " +
                "${context.packageName} ${Process.myUid()}"
            val p = runCatching { ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start() }
                .onFailure { Log.w(TAG, "su launch failed", it) }.getOrNull() ?: return false
            process = p
            // Drain the output so a chatty app_process can't fill its pipe and stall.
            Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { if (BuildConfig.DEBUG) Log.d(TAG, "su: $it") } }
            }.apply { isDaemon = true }.start()
            if (!got.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) return false
            val h = host ?: return false
            return runCatching { h.setCleanup(client, ""); true }.getOrDefault(false)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun mirror(surface: Surface, width: Int, height: Int): Boolean =
        runCatching { host?.mirror(surface, width, height) == true }.onFailure { Log.w(TAG, "mirror", it) }.getOrDefault(false)

    fun unmirror() {
        runCatching { host?.unmirror() }
    }

    fun touch(downTime: Long, eventTime: Long, action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        runCatching { host?.touch(downTime, eventTime, action, ids, xs, ys) }
    }

    fun key(action: Int, keyCode: Int) {
        runCatching { host?.key(action, keyCode) }
    }

    /** What [script] printed in the helper's root shell; null when the helper is gone. */
    fun shell(script: String): String? = runCatching { host?.shell(script) }.getOrNull()

    fun setCleanup(script: String) {
        runCatching { host?.setCleanup(client, script) }
    }

    fun usbDefault(): String? = runCatching { host?.usbDefault(context.packageName, Process.myUid()) }.getOrNull()

    fun release() {
        // The helper ends inside this call, so it fails with a dead binder: expected.
        runCatching { host?.exit() }
        host = null
        runCatching { process?.destroy() }
        process = null
    }

    private companion object {
        const val TAG = "AareCastLink"
        /** Long enough for the root manager's prompt the first time. */
        const val CONNECT_TIMEOUT_S = 20L
    }
}

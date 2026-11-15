package net.prototux.aare.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Root entry point for screen cast mode, run through
 * `su -c "AARE_CAST_TOKEN=<token> CLASSPATH=<apk> app_process / net.prototux.aare.cast.CastMain <appPackage> <appUid>"`,
 * the way [net.prototux.aare.launch.LaunchMain] runs the app launcher. It builds a [CastHostService]
 * and hands its binder back to the app with a broadcast aimed at the app's package, then serves it
 * on a Looper.
 *
 * The broadcast carries the random token the app put in the environment, so the app can tell this
 * helper from another app sending it a binder of its own (the receiver has to be exported: root is
 * another uid).
 *
 * Kept out of R8 by the release build having no minification, so the class and method survive
 * under this exact name for app_process to find.
 */
object CastMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val appPackage = args.getOrNull(0) ?: exitProcess(2)
        val appUid = args.getOrNull(1)?.toIntOrNull() ?: exitProcess(2)
        val token = System.getenv(ENV_TOKEN) ?: exitProcess(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        Looper.prepareMainLooper()
        val ctx = systemContext() ?: exitProcess(3)
        val service = CastHostService(appUid, ctx)
        val bundle = Bundle().apply {
            putBinder(EXTRA_BINDER, service)
            putString(EXTRA_TOKEN, token)
        }
        runCatching { ctx.sendBroadcast(Intent(ACTION_HOST).setPackage(appPackage).putExtra(EXTRA_BUNDLE, bundle)) }
            .onFailure { Log.w(TAG, "could not hand the binder back", it); exitProcess(4) }
        // Nobody took the binder (the app gave up waiting, or went away): don't stay around as root.
        Handler(Looper.getMainLooper()).postDelayed({ if (!service.claimed) exitProcess(0) }, CLAIM_TIMEOUT_MS)
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { Log.w(TAG, "no system context", it) }.getOrNull()

    const val ACTION_HOST = "net.prototux.aare.CAST_HOST"
    const val EXTRA_BUNDLE = "bundle"
    const val EXTRA_BINDER = "host"
    const val EXTRA_TOKEN = "token"
    const val ENV_TOKEN = "AARE_CAST_TOKEN"
    private const val CLAIM_TIMEOUT_MS = 30_000L
    private const val TAG = "AareCastMain"
}

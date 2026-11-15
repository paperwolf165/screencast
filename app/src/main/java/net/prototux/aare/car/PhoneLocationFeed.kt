package net.prototux.aare.car

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Feeds the car's GPS to the phone's own location providers while connected, so every app
 * (CoMaps, Google Maps…) sees one position. Otherwise a navigation app mixes the car's position,
 * which AARE gives car apps, with the phone's own fix, and the map jumps between the two.
 *
 * Android only allows it for the app chosen as "mock location app" in Developer options.
 */
class PhoneLocationFeed(context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }
    private val active = HashSet<String>()
    /** When taking over the providers was last refused (elapsedRealtime); 0: not refused. */
    @Volatile private var refusedAt = 0L
    /** What the connection log last said: providers taken (true), refused (false), nothing yet (null). */
    private var announced: Boolean? = null

    /** A new car fix (any thread). */
    @Synchronized
    fun push(car: Location) {
        // Refused: try again in a while. In screen cast mode root makes this app the mock location
        // app as the car connects, which can come after the car's first fix.
        if (refusedAt != 0L && SystemClock.elapsedRealtime() - refusedAt < RETRY_MS) return
        if (active.isEmpty()) start()
        for (p in active) {
            val l = Location(car).apply {
                provider = p
                if (!hasAccuracy()) accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            runCatching { lm.setTestProviderLocation(p, l) }.onFailure { Log.w(TAG, "setTestProviderLocation $p", it) }
        }
    }

    // The boolean form works on every Android version; Criteria's POWER_LOW and ACCURACY_FINE have
    // the same values as the ProviderProperties constants lint expects.
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun start() {
        for (p in providers) {
            runCatching {
                lm.addTestProvider(p, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
                active += p
            }.onFailure { Log.w(TAG, "addTestProvider $p", it) }
        }
        // Not the mock location app: don't try again for every fix.
        val took = active.isNotEmpty()
        refusedAt = if (took) 0L else SystemClock.elapsedRealtime()
        // One connection-log line per change, not one per retry.
        if (announced != took) {
            announced = took
            net.prototux.aare.DiagLog.add(
                if (took) "car GPS: the phone's location now comes from the car (${active.joinToString()})"
                else "car GPS: Android refused, this app is not the mock location app (retrying)")
        }
    }

    @Synchronized
    fun stop() {
        for (p in active) runCatching { lm.removeTestProvider(p) }
        active.clear()
        refusedAt = 0L
        announced = null
    }

    companion object {
        private const val TAG = "AareLocation"
        private const val RETRY_MS = 5_000L

        /** AARE is the phone's mock location app (Developer options > Select mock location app). */
        fun allowed(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}

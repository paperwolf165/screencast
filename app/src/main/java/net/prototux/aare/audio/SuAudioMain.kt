package net.prototux.aare.audio

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Entry point run as root by [SuSource], the way scrcpy and Shizuku run a helper: AARE launches
 *   `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.audio.SuAudioMain <rate> <channels>"`
 * and reads the phone's audio as raw 16-bit PCM from this process's stdout.
 *
 * It reuses [ShellAudioService] (the remote-submix capture, which drops the root process to the
 * shell uid so CAPTURE_AUDIO_OUTPUT is granted), so there is no separate root capture to maintain.
 *
 * With `policy <usage>,<usage>…` after the channels it captures through an audio policy instead
 * (ShellAudioService.startPolicy, as the shell, which holds MODIFY_AUDIO_ROUTING): the sound of
 * exactly those usages leaves the phone's speaker and comes here. That is how screen cast mode
 * takes every kind of sound, not only what the remote submix carries.
 * No minification in release (optimization disabled), so the class survives under this exact name.
 */
object SuAudioMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val rate = args.getOrNull(0)?.toIntOrNull() ?: 48_000
        val channels = args.getOrNull(1)?.toIntOrNull() ?: 2
        // The capture needs this process's system context (ShellAudioService.systemContext), and
        // making one creates a Handler: without a Looper on this thread that throws, and every
        // capture failed before it began. The launcher's and the cast's helpers prepare one too.
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val usages = if (args.getOrNull(2) == "policy") args.getOrNull(3)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toIntArray() else null
        val service = ShellAudioService(null)
        val opened = if (usages != null && usages.isNotEmpty()) {
            // The policy is registered and recorded as the shell; once dropped there is no way back, and none is needed.
            if (Process.myUid() == 0) runCatching { Os.setgid(SHELL_UID); Os.setuid(SHELL_UID) }.onFailure {
                System.err.println("$ERR could not become the shell user: $it")
                exitProcess(2)
            }
            service.startPolicy(0, rate, channels, usages)
        } else {
            service.start(rate, channels)
        }
        if (opened == null) System.err.println("$ERR no capture (see the lines above)")
        val pfd = opened ?: exitProcess(1)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val out = FileOutputStream(FileDescriptor.out)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // AARE stopped reading (closed its end of the pipe): we're done.
        } finally {
            service.stop()
            if (usages != null) service.stopPolicy(0)
        }
        exitProcess(0)
    }

    private const val SHELL_UID = 2000
    /** Starts the lines on stderr that the app copies into its connection log (SuSource). */
    const val ERR = "audio-helper:"
}

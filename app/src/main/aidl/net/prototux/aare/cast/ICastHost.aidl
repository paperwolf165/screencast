package net.prototux.aare.cast;

import android.view.Surface;

/**
 * The root helper behind screen cast mode: it mirrors the phone's own display into a surface of the
 * car screen, injects the car's touches and keys into the phone, and runs the root shell commands
 * that resize, rotate and dim the phone's display while casting.
 *
 * Implemented by CastHostService in a process started with su (CastMain), which hands this binder
 * back to the app over a broadcast. Every call is checked against the app's uid.
 */
interface ICastHost {
    /** Mirror the phone's main display into [surface] ([width] x [height] pixels). False if Android refused. */
    boolean mirror(in Surface surface, int width, int height) = 1;

    /** Stop mirroring (the helper stays, for the next [mirror]). */
    void unmirror() = 2;

    /** Inject a touch, in phone display pixels. One entry per pointer in [ids], [xs] and [ys]. */
    oneway void touch(long downTime, long eventTime, int action, in int[] ids, in float[] xs, in float[] ys) = 3;

    /** Inject a key event (KeyEvent.ACTION_DOWN or ACTION_UP). */
    oneway void key(int action, int keyCode) = 4;

    /** Run [script] in a root shell and return what it printed. */
    String shell(String script) = 5;

    /**
     * [script] runs if the app holding [client] dies, so the phone never stays resized, rotated or
     * dark after a crash; the helper exits afterwards. An empty script clears it.
     */
    void setCleanup(IBinder client, String script) = 6;

    /** Make [packageName] the default app for the attached USB accessory (the head unit). Returns a log line. */
    String usbDefault(String packageName, int uid) = 7;

    /** Stop mirroring and end the helper process. */
    void exit() = 8;
}

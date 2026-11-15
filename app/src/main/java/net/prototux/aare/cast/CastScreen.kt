package net.prototux.aare.cast

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import net.prototux.aare.R
import net.prototux.aare.car.ProjectionLayout
import net.prototux.aare.mirror.PhoneMirror

/**
 * The whole car screen in screen cast mode: the phone's display and nothing else (no bar, no
 * launcher, none of AARE's car interface), as large as it fits in the part of the video frame the
 * head unit shows, centred on black. The picture comes from the root
 * helper, which mirrors the phone's display into this view's surface; touches don't go through
 * this view at all (the session maps them straight into the phone with [CastController.touch]).
 */
@Composable
internal fun CastScreen(cast: CastController, layout: ProjectionLayout) {
    val context = LocalContext.current.applicationContext
    val status by cast.status.collectAsState()
    // The phone display's size follows its rotation and the resize the cast applies.
    var phone by remember { mutableStateOf(PhoneMirror.phoneScreenSize(context)) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) phone = PhoneMirror.phoneScreenSize(context)
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }

    val density = LocalDensity.current
    val pads = with(density) {
        // Only the margins, which the head unit never shows. The content insets (where a head unit
        // may draw a corner or a bar of its own) are not kept clear: the cast is the whole screen.
        Modifier.absolutePadding(left = layout.marginLeft.toDp(), top = layout.marginTop.toDp(), right = layout.marginRight.toDp(), bottom = layout.marginBottom.toDp())
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().then(pads), contentAlignment = Alignment.Center) {
            // The phone's shape, fitted in what the car shows: the mirror then fills this view
            // exactly, and a car pixel maps to a phone pixel with one scale.
            val pw = phone.x.coerceAtLeast(1)
            val ph = phone.y.coerceAtLeast(1)
            val scale = minOf(constraints.maxWidth.toFloat() / pw, constraints.maxHeight.toFloat() / ph)
            val w = (pw * scale).roundToInt().coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
            val h = (ph * scale).roundToInt().coerceIn(1, constraints.maxHeight.coerceAtLeast(1))
            val phoneNow = phone
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(st).also { cast.attach(it, width, height) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                surface?.let { cast.attach(it, width, height) }
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surface?.let { cast.detach(it); it.release() }
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = with(density) { Modifier.size(w.toDp(), h.toDp()) }.onGloballyPositioned { c ->
                    val p = c.positionInWindow()
                    val left = p.x.roundToInt()
                    val top = p.y.roundToInt()
                    cast.layout(Rect(left, top, left + c.size.width, top + c.size.height), phoneNow)
                },
                // Runs on every recomposition: a phone size that changed without moving the view still gets through.
                update = { v -> if (v.width > 0 && v.height > 0) cast.layout(currentRect(v), phoneNow) },
            )
            val message = when (status) {
                CastController.Status.MIRRORING -> null
                CastController.Status.STARTING -> stringResource(R.string.cast_status_starting)
                CastController.Status.NO_ROOT -> stringResource(R.string.cast_status_no_root)
                CastController.Status.FAILED -> stringResource(R.string.cast_status_failed)
            }
            if (message != null) {
                Text(message, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/** [v]'s place in its window, in pixels. */
private fun currentRect(v: android.view.View): Rect {
    val at = IntArray(2)
    v.getLocationInWindow(at)
    return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
}

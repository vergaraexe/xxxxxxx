package cl.lpa.agenda

import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** Recuerda cuando fue la ultima vez que el lapiz estuvo TOCANDO la pantalla (el lapiz solo "flotando" no cuenta). */
object PalmState {
    @Volatile
    var lastPenMs: Long = 0L

    fun penNearby(windowMs: Long = 350L): Boolean =
        SystemClock.uptimeMillis() - lastPenMs < windowMs
}

/**
 * Proteccion contra la palma: mientras el lapiz esta escribiendo (y hasta 0,35 s despues de levantarlo),
 * los toques de dedo/palma se descartan. Si el lapiz solo esta cerca o en la mano, el dedo funciona normal.
 * El lapiz siempre funciona.
 */
fun Modifier.palmGuard(guardMs: Long = 350L): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val now = SystemClock.uptimeMillis()
            for (c in event.changes) {
                if ((c.type == PointerType.Stylus || c.type == PointerType.Eraser) && c.pressed) {
                    PalmState.lastPenMs = now
                }
            }
            if (now - PalmState.lastPenMs < guardMs) {
                for (c in event.changes) {
                    if (c.type == PointerType.Touch) c.consume()
                }
            }
        }
    }
}

package cl.lpa.agenda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InkRecognizer.ensureModel()   // descarga (una vez) el reconocedor de escritura en espanol
        scheduleWeeklyMail()
        val app = applicationContext
        Thread {
            // deja UN solo Word por dia en Documentos/Agenda y termina los dias que quedaron pendientes
            MediaBackup.cleanDuplicateDocx(app, "Documents/Agenda")
            runCatching { DayStore(app).takeDirty().forEach { DocxExporter.sync(app, it) } }
            runCatching { CalendarAlerts.scheduleNext(app) }   // alarmas del calendario
        }.start()
        setContent {
            MaterialTheme {
                AgendaScreen()
            }
        }
        hideSystemBars()
    }

    /**
     * Pantalla inmersiva: la barra de navegacion del sistema (inicio / atras) queda oculta, asi un roce
     * con la palma en el borde de abajo ya no saca la agenda. Para verla hay que deslizar desde el borde.
     */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** El Word de cada dia se actualiza solo mientras se usa la agenda; al salir se terminan las pendientes. */
    override fun onStop() {
        super.onStop()
        val app = applicationContext
        DocxSync.flush(app)
        Thread {
            runCatching {
                // por si alguna actualizacion quedo pendiente de una sesion anterior
                DayStore(app).takeDirty().forEach { DocxExporter.sync(app, it) }
            }
        }.start()
    }

    /** Programa el envio semanal: domingo 23:55 (y una revision diaria de respaldo). */
    private fun scheduleWeeklyMail() {
        WeeklyScheduler.schedule(this)
    }
}

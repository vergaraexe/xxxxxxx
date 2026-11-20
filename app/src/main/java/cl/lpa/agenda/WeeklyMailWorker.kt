package cl.lpa.agenda

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Envio semanal: el domingo a las 23:55 se envian los 7 Word de la semana (lunes a domingo).
 *  - "Tarea del domingo": una tarea programada para el proximo domingo 23:55; al correr deja programada la siguiente.
 *  - "Red de seguridad": una revision diaria (~08:00) que envia la semana anterior si el domingo no se pudo
 *    (tablet apagada o sin internet). Nunca se envia dos veces la misma semana.
 * Android puede atrasar unos minutos las tareas programadas para ahorrar bateria.
 */
class WeeklyMailWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        // la tarea del domingo siempre deja programada la del domingo siguiente, pase lo que pase
        if (inputData.getBoolean("chain", false)) WeeklyScheduler.schedule(ctx)

        if (!MailSettings.isConfigured(MailSettings.load(ctx))) return Result.success()

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val weekEnd = WeeklyReport.lastSunday(today)
        val afterSunday = today.isAfter(weekEnd)
        val due = afterSunday || !now.toLocalTime().isBefore(LocalTime.of(23, 55))
        if (!due) return Result.success()

        val last = MailSettings.lastSent(ctx)
        if (last == weekEnd.toString()) return Result.success()
        if (last.isEmpty() && afterSunday) {
            // instalacion nueva: no se envia una semana antigua; se parte desde el proximo domingo
            MailSettings.setLastSent(ctx, weekEnd.toString())
            return Result.success()
        }

        return try {
            WeeklyReport.sendWeek(ctx, weekEnd)
            MailSettings.setLastSent(ctx, weekEnd.toString())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object WeeklyScheduler {
    fun nextSunday2355(from: LocalDateTime): LocalDateTime {
        var d = from.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(23, 55)
        if (!d.isAfter(from)) d = d.plusWeeks(1)
        return d
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val now = LocalDateTime.now()

        // 1) tarea del proximo domingo 23:55
        val next = nextSunday2355(now)
        val sunday = OneTimeWorkRequestBuilder<WeeklyMailWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("chain" to true))
            .setConstraints(online)
            .build()
        wm.enqueueUniqueWork("weekly_mail_${next.toLocalDate()}", ExistingWorkPolicy.KEEP, sunday)

        // 2) red de seguridad diaria (~08:00)
        var morning = now.toLocalDate().atTime(8, 0)
        if (!morning.isAfter(now)) morning = morning.plusDays(1)
        val catchUp = PeriodicWorkRequestBuilder<WeeklyMailWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, morning).toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(online)
            .build()
        wm.enqueueUniquePeriodicWork("weekly_mail_catchup", ExistingPeriodicWorkPolicy.KEEP, catchUp)

        // la programacion antigua (20:00) ya no se usa
        wm.cancelUniqueWork("weekly_mail")
    }
}

package cl.lpa.agenda

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Un aviso ya vencido que sigue en pantalla hasta que se cierre manteniendo el boton 3 segundos. */
data class PendingAlert(val key: String, val title: String, val text: String)

object CalendarAlerts {
    private const val CHANNEL = "cal_alerts"
    private const val STALE_MS = 12L * 60 * 60 * 1000   // un aviso que vencio hace mas de 12 h ya no se muestra

    private data class Trigger(val at: Long, val key: String, val text: String)

    fun firedPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("cal_alerts", Context.MODE_PRIVATE)

    private fun millis(d: LocalDate, t: LocalTime): Long =
        LocalDateTime.of(d, t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun humanMinutes(m: Int): String = when {
        m % 1440 == 0 -> if (m / 1440 == 1) "1 día" else "${m / 1440} días"
        m % 60 == 0 -> if (m / 60 == 1) "1 hora" else "${m / 60} horas"
        else -> "$m minutos"
    }

    private fun triggersFor(e: CalEvent, cfg: AlertConfig): List<Trigger> {
        val out = ArrayList<Trigger>()
        val d = runCatching { e.localDate() }.getOrNull() ?: return out
        val t = e.localTime()
        val where = if (e.place.isBlank()) "" else " · ${e.place}"
        val stamp = "${e.date}${e.time}"
        if (cfg.dayBeforeOn) {
            val at = millis(d.minusDays(1), LocalTime.of(cfg.dayBeforeHour, cfg.dayBeforeMinute))
            val whenTxt = if (t != null) "Mañana a las ${e.time}" else "Mañana"
            out.add(Trigger(at, "${e.id}|db|$stamp", "$whenTxt: ${e.name}$where"))
        }
        if (cfg.beforeOn && t != null) {
            val at = millis(d, t) - cfg.beforeMinutes * 60_000L
            out.add(Trigger(at, "${e.id}|bf|$stamp", "En ${humanMinutes(cfg.beforeMinutes)} (${e.time}): ${e.name}$where"))
        }
        return out
    }

    // ---------- avisos en pantalla ----------
    fun pending(ctx: Context): List<PendingAlert> {
        val raw = firedPrefs(ctx).getString("pending", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                PendingAlert(o.getString("k"), o.getString("ti"), o.getString("tx"))
            }
        }.getOrDefault(emptyList())
    }

    private fun savePending(ctx: Context, list: List<PendingAlert>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("k", it.key).put("ti", it.title).put("tx", it.text)) }
        firedPrefs(ctx).edit().putString("pending", a.toString()).apply()
    }

    @Synchronized
    fun dismiss(ctx: Context, key: String) {
        savePending(ctx, pending(ctx).filter { it.key != key })
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(key.hashCode())
        }
    }

    /** Pasa a "pendientes" los avisos cuya hora ya llego y los notifica. Devuelve los nuevos. */
    @Synchronized
    fun collectDue(ctx: Context, notify: Boolean = true): List<PendingAlert> {
        val now = System.currentTimeMillis()
        val cfg = CalendarStore(ctx).alertConfig()
        val prefs = firedPrefs(ctx)
        val fired = prefs.getStringSet("fired", emptySet())!!.toMutableSet()
        val pend = pending(ctx).toMutableList()
        val fresh = ArrayList<PendingAlert>()
        for (e in CalendarStore(ctx).loadEvents()) {
            for (tr in triggersFor(e, cfg)) {
                if (tr.at > now || tr.key in fired) continue
                fired.add(tr.key)
                if (now - tr.at <= STALE_MS) {
                    val p = PendingAlert(tr.key, "Recordatorio", tr.text)
                    pend.add(p); fresh.add(p)
                }
            }
        }
        // limpia marcas de eventos que ya no existen
        prefs.edit().putStringSet("fired", fired).apply()
        savePending(ctx, pend)
        if (notify) fresh.forEach { notifyAlert(ctx, it) }
        return fresh
    }

    /** Alerta de prueba inmediata, para comprobar que el aviso aparece y se cierra con 3 segundos. */
    @Synchronized
    fun test(ctx: Context) {
        val a = PendingAlert("test|${System.currentTimeMillis()}", "Recordatorio de prueba", "Así se verá el aviso de un evento.")
        savePending(ctx, pending(ctx) + a)
        notifyAlert(ctx, a)
    }

    /** Programa UNA alarma: la del proximo aviso. Al sonar se programa la siguiente. */
    @Synchronized
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 7001, Intent(ctx, AlertReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val cfg = CalendarStore(ctx).alertConfig()
        val fired = firedPrefs(ctx).getStringSet("fired", emptySet())!!
        var next = Long.MAX_VALUE
        for (e in CalendarStore(ctx).loadEvents())
            for (tr in triggersFor(e, cfg))
                if (tr.key !in fired && tr.at > now && tr.at < next) next = tr.at
        am.cancel(pi)
        if (next == Long.MAX_VALUE) return
        // setAlarmClock es exacta y no necesita el permiso de "alarmas y recordatorios"
        val show = PendingIntent.getActivity(
            ctx, 7002, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    private fun notifyAlert(ctx: Context, a: PendingAlert) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alertas del calendario", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 7003, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(false)
            .build()
        try {
            nm.notify(a.key.hashCode(), n)
        } catch (e: SecurityException) {
            // sin permiso de notificaciones: el aviso igual sale dentro de la app
        }
    }
}

/** Suena la alarma: marca los avisos vencidos, los notifica y programa el siguiente. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        CalendarAlerts.collectDue(app)
        CalendarAlerts.scheduleNext(app)
    }
}

/** Despues de reiniciar la tablet o actualizar la app, se vuelven a programar los avisos. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        CalendarAlerts.collectDue(app)
        CalendarAlerts.scheduleNext(app)
    }
}

package cl.lpa.agenda

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pasa los eventos al Google Calendar de la tablet (cuenta Google ya iniciada en Android).
 * Android lo sincroniza con la nube y Google envia la invitacion a los correos de "Involucrados".
 */
object CalendarSync {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun findCalendar(ctx: Context, wanted: String): Pair<Long, String>? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.OWNER_ACCOUNT
        )
        val cur = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf("com.google", "700"), null
        ) ?: return null
        var best: Pair<Long, String>? = null
        cur.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val acc = c.getString(1) ?: ""
                val primary = c.getInt(2) == 1
                val owner = c.getString(3) ?: ""
                if (wanted.isNotBlank()) {
                    if (acc.equals(wanted, ignoreCase = true)) return Pair(id, acc)
                    continue
                }
                if (primary || owner == acc) return Pair(id, acc)
                if (best == null) best = Pair(id, acc)
            }
        }
        return best
    }

    /** Crea o actualiza el evento. Devuelve (id del evento en el calendario del equipo, mensaje). */
    fun push(ctx: Context, e: CalEvent): Pair<Long, String> {
        if (!hasPermission(ctx)) return Pair(e.gid, "Falta el permiso de Calendario: no se agregó a Google Calendar.")
        // La cuenta es la que se escribió en Ajustes (correo de envío)
        val wanted = runCatching { MailSettings.load(ctx).sender.trim() }.getOrDefault("")
        val cal = findCalendar(ctx, wanted)
            ?: return Pair(
                e.gid,
                if (wanted.isNotBlank())
                    "La cuenta $wanted (la de Ajustes) no está agregada en la tablet (Ajustes de Android → Cuentas → Google): el evento quedó solo en la app."
                else "No hay una cuenta de Google con calendario en esta tablet: el evento quedó solo en la app."
            )
        val (calId, account) = cal
        val d = e.localDate()
        val t = e.localTime()
        val v = ContentValues()
        v.put(CalendarContract.Events.CALENDAR_ID, calId)
        v.put(CalendarContract.Events.TITLE, e.name)
        v.put(CalendarContract.Events.EVENT_LOCATION, e.place)
        v.put(CalendarContract.Events.ORGANIZER, account)
        v.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        if (t == null) {
            val start = d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            v.put(CalendarContract.Events.ALL_DAY, 1)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            v.put(CalendarContract.Events.DTSTART, start)
            v.put(CalendarContract.Events.DTEND, start + 86_400_000L)
        } else {
            val zone = ZoneId.systemDefault()
            val start = d.atTime(t).atZone(zone).toInstant().toEpochMilli()
            v.put(CalendarContract.Events.ALL_DAY, 0)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            v.put(CalendarContract.Events.DTSTART, start)
            v.put(CalendarContract.Events.DTEND, start + 3_600_000L)
        }
        return try {
            val res = ctx.contentResolver
            var id = e.gid
            var updated = false
            if (id > 0) {
                updated = res.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null) > 0
            }
            if (!updated) {
                val uri = res.insert(CalendarContract.Events.CONTENT_URI, v)
                    ?: return Pair(e.gid, "No se pudo crear el evento en Google Calendar.")
                id = ContentUris.parseId(uri)
            } else {
                res.delete(CalendarContract.Attendees.CONTENT_URI, "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(id.toString()))
            }
            if (e.people.isNotEmpty()) {
                val org = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, id)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, account)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ORGANIZER)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
                }
                res.insert(CalendarContract.Attendees.CONTENT_URI, org)
                for (p in e.people) {
                    if (p.equals(account, ignoreCase = true)) continue
                    val a = ContentValues().apply {
                        put(CalendarContract.Attendees.EVENT_ID, id)
                        put(CalendarContract.Attendees.ATTENDEE_EMAIL, p)
                        put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                        put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                        put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                    }
                    res.insert(CalendarContract.Attendees.CONTENT_URI, a)
                }
            }
            val msg = "Agregado a Google Calendar ($account)" +
                (if (e.people.isEmpty()) "." else "; Google enviará la invitación a ${e.people.size} persona(s) al sincronizar.")
            Pair(id, msg)
        } catch (ex: Exception) {
            Pair(e.gid, "No se pudo agregar a Google Calendar: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    fun remove(ctx: Context, gid: Long) {
        if (gid <= 0 || !hasPermission(ctx)) return
        runCatching {
            ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, gid), null, null)
        }
    }

    /** Hace push en segundo plano, guarda el id y avisa con un mensaje en pantalla. */
    fun pushAsync(ctx: Context, store: CalendarStore, e: CalEvent, onGid: (Long) -> Unit = {}) {
        val app = ctx.applicationContext
        Thread {
            val (gid, msg) = push(app, e)
            if (gid != e.gid) store.upsert(e.copy(gid = gid))
            Handler(Looper.getMainLooper()).post {
                onGid(gid)
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}

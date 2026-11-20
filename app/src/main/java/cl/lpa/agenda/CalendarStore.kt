package cl.lpa.agenda

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Un evento del calendario. [time] vacio = evento de todo el dia. */
data class CalEvent(
    val id: String,
    val name: String,
    val date: String,          // yyyy-MM-dd
    val time: String,          // HH:mm o ""
    val place: String,
    val people: List<String>,
    val gid: Long = 0L          // id del evento en el Google Calendar de la tablet (0 = aun no)
) {
    fun localDate(): LocalDate = LocalDate.parse(date)
    fun localTime(): LocalTime? = if (time.isBlank()) null else runCatching { LocalTime.parse(time) }.getOrNull()
}

/** Ajustes de alertas. Por defecto: 8:00 del dia anterior y 1 hora antes. */
data class AlertConfig(
    val dayBeforeOn: Boolean = true,
    val dayBeforeHour: Int = 8,
    val dayBeforeMinute: Int = 0,
    val beforeOn: Boolean = true,
    val beforeMinutes: Int = 60
)

class CalendarStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "calendar").apply { mkdirs() }
    private val eventsFile get() = File(dir, "events.json")
    private val contactsFile get() = File(dir, "contacts.json")

    @Synchronized
    fun loadEvents(): List<CalEvent> {
        if (!eventsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(eventsFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val p = o.optJSONArray("p") ?: JSONArray()
                CalEvent(
                    o.getString("id"), o.optString("n"), o.getString("d"), o.optString("t"), o.optString("l"),
                    (0 until p.length()).map { p.getString(it) },
                    o.optLong("g", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveEvents(list: List<CalEvent>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("n", e.name).put("d", e.date).put("t", e.time)
                    .put("l", e.place).put("p", JSONArray(e.people)).put("g", e.gid)
            )
        }
        val tmp = File(dir, "events.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(eventsFile)
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Guarda (crea o reemplaza) un evento y recuerda los correos nuevos. */
    fun upsert(e: CalEvent) {
        val list = loadEvents().filter { it.id != e.id } + e
        saveEvents(list)
        e.people.forEach { addContact(it) }
    }

    fun delete(id: String) {
        saveEvents(loadEvents().filter { it.id != id })
        val fired = CalendarAlerts.firedPrefs(ctx)
        val keep = fired.getStringSet("fired", emptySet())!!.filterNot { it.startsWith("$id|") }.toSet()
        fired.edit().putStringSet("fired", keep).apply()
    }

    // ---------- Base de correos (para autocompletar) ----------
    /** correo -> veces usado */
    @Synchronized
    fun contacts(): List<Pair<String, Int>> {
        if (!contactsFile.exists()) return emptyList()
        return runCatching {
            val o = JSONObject(contactsFile.readText())
            o.keys().asSequence().map { it to o.optInt(it, 1) }.toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addContact(email: String) {
        val m = email.trim().lowercase()
        if (!isEmail(m)) return
        val o = if (contactsFile.exists()) runCatching { JSONObject(contactsFile.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        o.put(m, o.optInt(m, 0) + 1)
        contactsFile.writeText(o.toString())
    }

    @Synchronized
    fun removeContact(email: String) {
        if (!contactsFile.exists()) return
        val o = runCatching { JSONObject(contactsFile.readText()) }.getOrDefault(JSONObject())
        o.remove(email)
        contactsFile.writeText(o.toString())
    }

    // ---------- Ajustes de alertas ----------
    fun alertConfig(): AlertConfig {
        val p = ctx.getSharedPreferences("cal_prefs", Context.MODE_PRIVATE)
        return AlertConfig(
            p.getBoolean("dbOn", true), p.getInt("dbH", 8), p.getInt("dbM", 0),
            p.getBoolean("bfOn", true), p.getInt("bfMin", 60)
        )
    }

    fun saveAlertConfig(c: AlertConfig) {
        ctx.getSharedPreferences("cal_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("dbOn", c.dayBeforeOn).putInt("dbH", c.dayBeforeHour).putInt("dbM", c.dayBeforeMinute)
            .putBoolean("bfOn", c.beforeOn).putInt("bfMin", c.beforeMinutes).apply()
    }

    companion object {
        fun isEmail(s: String) = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$").matches(s.trim())

        /** Convierte lo dictado ("juan arroba gmail punto com") en un correo. */
        fun spokenToEmail(raw: String): String {
            var s = raw.lowercase().trim()
            s = s.replace(" arroba ", "@").replace("arroba", "@")
                .replace(" punto ", ".").replace("punto", ".")
                .replace(" guion bajo ", "_").replace(" guion ", "-")
                .replace(" at ", "@").replace(" dot ", ".")
            return s.replace(" ", "")
        }
    }
}

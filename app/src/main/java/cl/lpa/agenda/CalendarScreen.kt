package cl.lpa.agenda

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val calNavy = Color(0xFF1E2A5A)
private val calSoft = Color(0xFFE8ECF7)
private val esCL = Locale("es", "CL")

private fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }

private fun fmtDate(d: LocalDate): String =
    cap(d.dayOfWeek.getDisplayName(TextStyle.FULL, esCL)) + " " + d.dayOfMonth + " de " +
        d.month.getDisplayName(TextStyle.FULL, esCL) + " de " + d.year

private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

/** Boton de voz: abre el dictado de Android y entrega el texto reconocido. */
@Composable
private fun rememberVoice(onText: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val current = rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) current.value(t)
    }
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora")
            )
        } catch (e: Exception) {
            toast(ctx, "Este equipo no tiene dictado por voz disponible.")
        }
    }
}

// =====================================================================================
//  Pantalla del calendario (tercer modulo)
// =====================================================================================
@Composable
fun CalendarScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CalendarStore(ctx) }
    var ymText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val ym = remember(ymText) { YearMonth.parse(ymText) }
    var events by remember { mutableStateOf(store.loadEvents()) }
    var editing by remember { mutableStateOf<CalEvent?>(null) }
    var newDate by remember { mutableStateOf<LocalDate?>(null) }
    var listDay by remember { mutableStateOf<LocalDate?>(null) }
    var showContacts by remember { mutableStateOf(false) }
    var showAlerts by remember { mutableStateOf(false) }
    var busyPdf by remember { mutableStateOf(false) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS")
        need.add("android.permission.READ_CALENDAR")
        need.add("android.permission.WRITE_CALENDAR")
        val missing = need.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) perms.launch(missing.toTypedArray())
    }

    fun reload() {
        events = store.loadEvents()
        CalendarAlerts.scheduleNext(ctx)
    }

    val first = ym.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val rows = (offset + ym.lengthOfMonth() + 6) / 7
    val today = LocalDate.now()
    val byDate = remember(events) { events.groupBy { it.date }.mapValues { (_, v) -> v.sortedBy { it.time } } }

    Column(
        modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFFFEFA))
    ) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().background(calSoft).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DialogButton("◀") { ymText = ym.minusMonths(1).toString() }
            Text(
                CalendarPdf.monthName(ym), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = calNavy,
                modifier = Modifier.widthIn(min = 190.dp)
            )
            DialogButton("▶") { ymText = ym.plusMonths(1).toString() }
            DialogButton("Hoy") { ymText = YearMonth.now().toString() }
            Spacer(Modifier.weight(1f))
            DialogButton("🔔 Alertas") { showAlerts = true }
            DialogButton("👥 Correos") { showContacts = true }
            DialogButton("📄 PDF del mes", primary = true) {
                if (!busyPdf) {
                    busyPdf = true
                    scope.launch(Dispatchers.IO) {
                        val f = runCatching { CalendarPdf.create(ctx, ym, store.loadEvents()) }.getOrNull()
                        if (f != null) runCatching { MediaBackup.copy(ctx, f, "Documents/Calendario", MediaBackup.PDF) }
                        withContext(Dispatchers.Main) {
                            busyPdf = false
                            if (f != null) openPdf(ctx, f) else toast(ctx, "No se pudo crear el PDF.")
                        }
                    }
                }
            }
        }
        // Dias de la semana
        Row(Modifier.fillMaxWidth().background(calNavy)) {
            listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom").forEach {
                Text(
                    it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f).padding(vertical = 5.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        // Cuadricula del mes
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 7) {
                    val d = first.plusDays((r * 7 + c - offset).toLong())
                    DayCell(
                        d = d, inMonth = d.month == ym.month, isToday = d == today,
                        evs = byDate[d.toString()].orEmpty(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onDay = { newDate = d },
                        onEvent = { editing = it },
                        onMore = { listDay = d }
                    )
                }
            }
        }
    }

    if (newDate != null) {
        EventDialog(store, null, newDate!!, onClose = { newDate = null }, onChanged = { reload() })
    }
    editing?.let { ev ->
        EventDialog(store, ev, ev.localDate(), onClose = { editing = null }, onChanged = { reload() })
    }
    listDay?.let { d ->
        DayListDialog(
            d, byDate[d.toString()].orEmpty(),
            onClose = { listDay = null },
            onEdit = { listDay = null; editing = it },
            onNew = { listDay = null; newDate = d }
        )
    }
    if (showContacts) ContactsDialog(store) { showContacts = false }
    if (showAlerts) AlertSettingsDialog(store) { showAlerts = false; reload() }
    if (busyPdf) BusyDialog("Creando el PDF del mes…")
}

@Composable
private fun DayCell(
    d: LocalDate, inMonth: Boolean, isToday: Boolean, evs: List<CalEvent>, modifier: Modifier,
    onDay: () -> Unit, onEvent: (CalEvent) -> Unit, onMore: () -> Unit
) {
    val weekend = d.dayOfWeek.value >= 6
    val bg = when {
        !inMonth -> Color(0xFFEDEDED)
        weekend -> Color(0xFFF3F5FB)
        else -> Color.White
    }
    BoxWithConstraints(
        modifier
            .border(0.5.dp, Color(0xFFBFC6DD))
            .background(bg)
            .clickable(onClick = onDay)
    ) {
        val avail = ((maxHeight - 30.dp) / 19.dp).toInt().coerceAtLeast(0)
        val show = if (evs.size > avail) (avail - 1).coerceAtLeast(0) else evs.size
        Column(Modifier.padding(3.dp)) {
            Box(
                Modifier.size(26.dp).background(if (isToday) calNavy else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    d.dayOfMonth.toString(), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        isToday -> Color.White
                        !inMonth -> Color(0xFF999999)
                        else -> calNavy
                    }
                )
            }
            evs.take(show).forEach { e ->
                Text(
                    (if (e.time.isBlank()) "" else e.time + " ") + e.name,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF1A1A1A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(calSoft)
                        .clickable { onEvent(e) }
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            if (evs.size > show) {
                Text(
                    "+${evs.size - show} más", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C),
                    modifier = Modifier.padding(top = 2.dp).clickable { onMore() }
                )
            }
        }
    }
}

// =====================================================================================
//  Lista de los eventos de un dia (cuando no caben en la celda)
// =====================================================================================
@Composable
private fun DayListDialog(
    d: LocalDate, evs: List<CalEvent>, onClose: () -> Unit, onEdit: (CalEvent) -> Unit, onNew: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(fmtDate(d), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = calNavy)
                evs.forEach { e ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(calSoft)
                            .clickable { onEdit(e) }.padding(10.dp)
                    ) {
                        Text((if (e.time.isBlank()) "Todo el día" else e.time) + " · " + e.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (e.place.isNotBlank()) Text("📍 ${e.place}", fontSize = 14.sp)
                        if (e.people.isNotEmpty()) Text("👥 ${e.people.joinToString(", ")}", fontSize = 13.sp, color = Color(0xFF555555))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogButton("Cerrar") { onClose() }
                    DialogButton("＋ Nuevo evento", primary = true) { onNew() }
                }
            }
        }
    }
}

// =====================================================================================
//  Crear / editar un evento
// =====================================================================================
@Composable
private fun EventDialog(
    store: CalendarStore, initial: CalEvent?, day: LocalDate, onClose: () -> Unit, onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var date by remember { mutableStateOf(initial?.localDate() ?: day) }
    var time by remember { mutableStateOf(initial?.time ?: "") }
    var place by remember { mutableStateOf(initial?.place ?: "") }
    val people = remember { mutableStateListOf<String>().apply { initial?.let { addAll(it.people) } } }
    var emailIn by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf(initial?.id) }
    var gidState by remember { mutableStateOf(initial?.gid ?: 0L) }
    var useGoogle by remember { mutableStateOf(true) }   // evita duplicar el evento si se guarda dos veces
    val contacts = remember { store.contacts() }

    fun addEmail(raw: String): Boolean {
        val e = raw.trim().lowercase()
        if (!CalendarStore.isEmail(e)) { toast(ctx, "Ese correo no parece válido: $raw"); return false }
        if (e !in people) people.add(e)
        emailIn = ""
        return true
    }

    val voiceName = rememberVoice { name = cap(it) }
    val voicePlace = rememberVoice { place = cap(it) }
    val voiceMail = rememberVoice {
        val e = CalendarStore.spokenToEmail(it)
        if (CalendarStore.isEmail(e)) addEmail(e)
        else { emailIn = e; toast(ctx, "Revisa el correo dictado y toca “Agregar”.") }
    }

    fun build(): CalEvent? {
        if (emailIn.isNotBlank()) addEmail(emailIn)
        if (name.isBlank()) { toast(ctx, "Escribe el nombre del evento."); return null }
        return CalEvent(savedId ?: store.newId().also { savedId = it }, name.trim(), date.toString(), time, place.trim(), people.toList(), gidState)
    }

    fun save(): CalEvent? {
        val e = build() ?: return null
        store.upsert(e)
        onChanged()
        if (useGoogle) CalendarSync.pushAsync(ctx, store, e) { gidState = it }
        return e
    }

    fun sendInvite(e: CalEvent) {
        if (e.people.isEmpty()) { toast(ctx, "Agrega al menos un correo en “Involucrados”."); return }
        val cfg = runCatching { MailSettings.load(ctx) }.getOrNull()
        if (cfg == null || cfg.sender.isBlank() || cfg.password.isBlank()) {
            toast(ctx, "Primero configura el correo en Ajustes (⚙️).")
            return
        }
        toast(ctx, "Enviando invitación…")
        scope.launch(Dispatchers.IO) {
            val msg = try {
                val ics = CalendarInvite.ics(ctx, e, cfg.sender)
                MailSender.send(cfg, "Invitación: ${e.name}", CalendarInvite.body(e), listOf(ics), e.people.joinToString(","))
                "Invitación enviada a ${e.people.size} persona(s)."
            } catch (ex: Exception) {
                "No se pudo enviar: ${ex.message ?: ex.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) { toast(ctx, msg) }
        }
    }

    fun openGoogle(e: CalEvent) {
        val begin = e.localDate().atTime(e.localTime() ?: LocalTime.of(9, 0))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, e.name)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, e.place)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.time.isBlank())
            .putExtra(Intent.EXTRA_EMAIL, e.people.joinToString(","))
        try {
            ctx.startActivity(i)
        } catch (ex: Exception) {
            toast(ctx, "No hay una app de calendario (Google Calendar) instalada en la tablet.")
        }
    }

    val sugg = contacts.map { it.first }.filter {
        it !in people && (emailIn.isBlank() || it.contains(emailIn.trim().lowercase()))
    }.take(8)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.72f)) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (initial == null) "Nuevo evento" else "Editar evento",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = calNavy
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Nombre de evento") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    DialogButton("🎤") { voiceName() }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fecha:", fontSize = 15.sp)
                    DialogButton(fmtDate(date)) {
                        DatePickerDialog(ctx, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd) },
                            date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }
                    Text("Hora:", fontSize = 15.sp)
                    DialogButton(if (time.isBlank()) "Sin hora" else time) {
                        val t = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.of(9, 0))
                        TimePickerDialog(ctx, { _, h, mi -> time = "%02d:%02d".format(h, mi) }, t.hour, t.minute, true).show()
                    }
                    if (time.isNotBlank()) DialogButton("Quitar hora") { time = "" }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = place, onValueChange = { place = it }, label = { Text("Lugar") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    DialogButton("🎤") { voicePlace() }
                }

                Text("Involucrados (correos)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = calNavy)
                people.toList().forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(calSoft).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text("✕", fontSize = 18.sp, color = Color(0xFFB71C1C),
                            modifier = Modifier.clickable { people.remove(p) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emailIn, onValueChange = { emailIn = it }, label = { Text("Correo (se autocompleta)") },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    DialogButton("🎤") { voiceMail() }
                    DialogButton("Agregar", primary = true, enabled = emailIn.isNotBlank()) { addEmail(emailIn) }
                }
                if (sugg.isNotEmpty()) {
                    Text("Correos guardados (toca para agregar):", fontSize = 13.sp, color = Color(0xFF555555))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sugg.forEach { s ->
                            Text(
                                s, fontSize = 14.sp, color = calNavy,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFD9E0F5))
                                    .clickable { addEmail(s) }.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Text("Google Calendar", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = calNavy)
                Text(
                    (if (useGoogle) "☑" else "☐") + "  Al guardar, agregar a mi Google Calendar y enviar la invitación a los involucrados",
                    fontSize = 15.sp, modifier = Modifier.clickable { useGoogle = !useGoogle }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    DialogButton("Abrir en la app Google Calendar") { save()?.let { openGoogle(it) } }
                    DialogButton("📧 Enviar invitación por correo (respaldo)") { save()?.let { sendInvite(it) } }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    DialogButton("Cancelar") { onClose() }
                    if (initial != null) DialogButton("Eliminar") { confirmDelete = true }
                    DialogButton("Guardar", primary = true) { if (save() != null) onClose() }
                }
            }
        }
    }

    if (confirmDelete && initial != null) {
        ConfirmDialog(
            "¿Eliminar el evento “${initial.name}”?",
            onYes = { CalendarSync.remove(ctx, initial.gid); store.delete(initial.id); onChanged(); confirmDelete = false; onClose() },
            onNo = { confirmDelete = false }
        )
    }
}

// =====================================================================================
//  Base de correos guardados
// =====================================================================================
@Composable
private fun ContactsDialog(store: CalendarStore, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(store.contacts()) }
    var input by remember { mutableStateOf("") }
    val voice = rememberVoice {
        val e = CalendarStore.spokenToEmail(it)
        if (CalendarStore.isEmail(e)) { store.addContact(e); list = store.contacts() } else input = e
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.6f)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Correos guardados", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = calNavy, modifier = Modifier.weight(1f))
                    DialogButton("Cerrar", primary = true) { onClose() }
                }
                Text(
                    "Cada correo que escribes en un evento queda guardado aquí y se ofrece la próxima vez.",
                    fontSize = 13.sp, color = Color(0xFF555555)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, label = { Text("Nuevo correo") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    DialogButton("🎤") { voice() }
                    DialogButton("Guardar", primary = true, enabled = input.isNotBlank()) {
                        if (CalendarStore.isEmail(input)) { store.addContact(input); list = store.contacts(); input = "" }
                        else toast(ctx, "Ese correo no parece válido.")
                    }
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) Text("Todavía no hay correos guardados.", fontSize = 14.sp)
                    list.forEach { (mail, n) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(calSoft).padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("$mail  ($n)", fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("✕", fontSize = 18.sp, color = Color(0xFFB71C1C),
                                modifier = Modifier.clickable { store.removeContact(mail); list = store.contacts() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Alertas configurables
// =====================================================================================
@Composable
private fun AlertSettingsDialog(store: CalendarStore, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember { store.alertConfig() }
    var dbOn by remember { mutableStateOf(initial.dayBeforeOn) }
    var dbH by remember { mutableIntStateOf(initial.dayBeforeHour) }
    var dbM by remember { mutableIntStateOf(initial.dayBeforeMinute) }
    var bfOn by remember { mutableStateOf(initial.beforeOn) }
    var bfMin by remember { mutableIntStateOf(initial.beforeMinutes) }
    var bfText by remember { mutableStateOf(initial.beforeMinutes.toString()) }

    fun save() {
        store.saveAlertConfig(AlertConfig(dbOn, dbH, dbM, bfOn, bfMin.coerceAtLeast(1)))
    }

    Dialog(onDismissRequest = { save(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.62f)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alertas", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = calNavy, modifier = Modifier.weight(1f))
                    DialogButton("Listo", primary = true) { save(); onClose() }
                }
                Text(
                    "Cada evento avisa con estas alertas. El aviso aparece en la pantalla principal y no se va hasta que mantengas " +
                        "presionado el botón 3 segundos.",
                    fontSize = 13.sp, color = Color(0xFF555555)
                )

                Text(
                    (if (dbOn) "☑" else "☐") + "  El día anterior a una hora fija",
                    fontSize = 16.sp, modifier = Modifier.clickable { dbOn = !dbOn }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hora del aviso:", fontSize = 15.sp)
                    DialogButton("%02d:%02d".format(dbH, dbM), enabled = dbOn) {
                        TimePickerDialog(ctx, { _, h, m -> dbH = h; dbM = m }, dbH, dbM, true).show()
                    }
                }

                Text(
                    (if (bfOn) "☑" else "☐") + "  Un rato antes del evento",
                    fontSize = 16.sp, modifier = Modifier.clickable { bfOn = !bfOn }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bfText,
                        onValueChange = { t ->
                            val digits = t.filter { it.isDigit() }.take(5)
                            bfText = digits
                            bfMin = digits.toIntOrNull() ?: 0
                        },
                        label = { Text("Minutos antes") }, singleLine = true, enabled = bfOn,
                        modifier = Modifier.width(170.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 hora", 120 to "2 horas", 1440 to "1 día").forEach { (m, label) ->
                        DialogButton(label, enabled = bfOn) { bfMin = m; bfText = m.toString() }
                    }
                }
                Text(
                    "Los eventos “sin hora” solo avisan el día anterior.",
                    fontSize = 12.sp, color = Color(0xFF777777)
                )
                DialogButton("🔔 Probar una alerta ahora") {
                    save()
                    CalendarAlerts.test(ctx)
                    onClose()
                }
            }
        }
    }
}

// =====================================================================================
//  Aviso en la pantalla principal (se muestra en cualquier modulo)
// =====================================================================================
@Composable
fun AlertHost() {
    val ctx = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf(CalendarAlerts.pending(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                runCatching {
                    CalendarAlerts.collectDue(ctx)
                    CalendarAlerts.scheduleNext(ctx)
                }
            }
            pending = CalendarAlerts.pending(ctx)
            delay(15_000L)
        }
    }
    val a = pending.firstOrNull() ?: return
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth(0.55f)) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🔔 ${a.title}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
                Text(a.text, fontSize = 22.sp, color = Color(0xFF111111))
                if (pending.size > 1) Text("Hay ${pending.size - 1} aviso(s) más en espera.", fontSize = 14.sp, color = Color(0xFF555555))
                HoldButton("Mantén presionado 3 segundos para cerrar", holdMs = 3000L) {
                    CalendarAlerts.dismiss(ctx, a.key)
                    pending = CalendarAlerts.pending(ctx)
                }
            }
        }
    }
}

// =====================================================================================
//  Invitacion por correo (.ics)
// =====================================================================================
object CalendarInvite {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    fun body(e: CalEvent): String {
        val d = e.localDate()
        val hora = if (e.time.isBlank()) "todo el día" else "a las ${e.time}"
        return "Te invito al evento “${e.name}”.\n\n" +
            "Fecha: ${d.dayOfMonth}/${d.monthValue}/${d.year} ($hora)\n" +
            (if (e.place.isBlank()) "" else "Lugar: ${e.place}\n") +
            "\nAdjunto va el archivo del evento: ábrelo para agregarlo a tu calendario.\n\nLiceo Politécnico Arica"
    }

    fun ics(ctx: Context, e: CalEvent, organizer: String): File {
        val tz = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val d = e.localDate()
        val t = e.localTime()
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//LPA//Agenda LPA//ES\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\n")
        sb.append("UID:${e.id}@agendalpa\r\n")
        sb.append("DTSTAMP:${LocalDateTime.now(ZoneOffset.UTC).format(fmt)}Z\r\n")
        if (t == null) {
            val day = DateTimeFormatter.ofPattern("yyyyMMdd")
            sb.append("DTSTART;VALUE=DATE:${d.format(day)}\r\n")
            sb.append("DTEND;VALUE=DATE:${d.plusDays(1).format(day)}\r\n")
        } else {
            val start = LocalDateTime.of(d, t)
            sb.append("DTSTART;TZID=${tz.id}:${start.format(fmt)}\r\n")
            sb.append("DTEND;TZID=${tz.id}:${start.plusHours(1).format(fmt)}\r\n")
        }
        sb.append("SUMMARY:${esc(e.name)}\r\n")
        if (e.place.isNotBlank()) sb.append("LOCATION:${esc(e.place)}\r\n")
        sb.append("ORGANIZER:mailto:$organizer\r\n")
        e.people.forEach { sb.append("ATTENDEE;RSVP=TRUE:mailto:$it\r\n") }
        sb.append("END:VEVENT\r\nEND:VCALENDAR\r\n")
        val f = File(ctx.cacheDir, "evento.ics")
        f.writeText(sb.toString())
        return f
    }
}

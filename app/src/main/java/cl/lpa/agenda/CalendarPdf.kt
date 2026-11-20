package cl.lpa.agenda

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Exporta un mes completo a PDF (hoja horizontal): matriz tipo calendario con el logo en la esquina + detalle. */
object CalendarPdf {
    private const val W = 842
    private const val H = 595
    private val NAVY = Color.parseColor("#1E2A5A")
    private val SOFT = Color.parseColor("#E8ECF7")
    private val WEEKEND = Color.parseColor("#F3F5FB")

    fun monthName(ym: YearMonth): String =
        ym.month.getDisplayName(TextStyle.FULL, Locale("es", "CL")).replaceFirstChar { it.uppercase() } + " " + ym.year

    fun create(ctx: Context, ym: YearMonth, all: List<CalEvent>): File {
        val events = all.filter { runCatching { YearMonth.from(it.localDate()) == ym }.getOrDefault(false) }
            .sortedWith(compareBy({ it.date }, { it.time }))
        val byDay = events.groupBy { it.localDate().dayOfMonth }

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        drawMatrix(ctx, page.canvas, ym, byDay)
        doc.finishPage(page)

        // Detalle de eventos (paginas siguientes)
        if (events.isNotEmpty()) {
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
            val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#222222"); textSize = 11f }
            val cols = floatArrayOf(36f, 120f, 190f, 400f, 540f)   // fecha, hora, evento, lugar, involucrados
            fun newPage(): Canvas {
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                val c = page.canvas
                c.drawText("Detalle de eventos · ${monthName(ym)}", 36f, 44f, title)
                val bar = Paint().apply { color = NAVY }
                c.drawRect(36f, 58f, W - 36f, 80f, bar)
                listOf("Fecha", "Hora", "Evento", "Lugar", "Involucrados").forEachIndexed { i, s ->
                    c.drawText(s, cols[i] + 4f, 73f, head)
                }
                return c
            }
            var c = newPage()
            var y = 80f
            var shade = false
            for (e in events) {
                val people = e.people.joinToString(", ")
                val lines = listOf(
                    wrap(e.name, txt, cols[3] - cols[2] - 8f),
                    wrap(e.place, txt, cols[4] - cols[3] - 8f),
                    wrap(people, txt, W - 36f - cols[4] - 8f)
                )
                val n = maxOf(1, lines.maxOf { it.size })
                val rowH = n * 14f + 8f
                if (y + rowH > H - 30f) {
                    doc.finishPage(page); c = newPage(); y = 80f
                }
                if (shade) c.drawRect(36f, y, W - 36f, y + rowH, Paint().apply { color = SOFT })
                shade = !shade
                val d = e.localDate()
                c.drawText("%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year), cols[0] + 4f, y + 16f, txt)
                c.drawText(if (e.time.isBlank()) "Todo el día" else e.time, cols[1] + 4f, y + 16f, txt)
                lines[0].forEachIndexed { i, s -> c.drawText(s, cols[2] + 4f, y + 16f + i * 14f, txt) }
                lines[1].forEachIndexed { i, s -> c.drawText(s, cols[3] + 4f, y + 16f + i * 14f, txt) }
                lines[2].forEachIndexed { i, s -> c.drawText(s, cols[4] + 4f, y + 16f + i * 14f, txt) }
                y += rowH
            }
            doc.finishPage(page)
        }

        val dir = File(ctx.filesDir, "calendar").apply { mkdirs() }
        val out = File(dir, "Calendario_%d-%02d.pdf".format(ym.year, ym.monthValue))
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = ArrayList<String>()
        var cur = ""
        for (w in text.split(" ", ",").filter { it.isNotEmpty() }) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(t) <= maxW) cur = t
            else { if (cur.isNotEmpty()) out.add(cur); cur = w }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out.ifEmpty { listOf("") }
    }

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawMatrix(ctx: Context, c: Canvas, ym: YearMonth, byDay: Map<Int, List<CalEvent>>) {
        // Encabezado: logo LPA en la esquina superior izquierda, mes y anio al centro
        val logo = BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_lpa)
        c.drawRect(0f, 0f, W.toFloat(), 64f, Paint().apply { color = Color.WHITE })
        if (logo != null) {
            val th = 50f
            val tw = logo.width * th / logo.height
            c.drawBitmap(logo, null, RectF(24f, 8f, 24f + tw, 8f + th), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NAVY; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(monthName(ym), W / 2f, 42f, title)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#666666"); textSize = 10f; textAlign = Paint.Align.RIGHT
        }
        c.drawText("Liceo Politécnico Arica", W - 24f, 30f, small)
        c.drawText("Calendario mensual", W - 24f, 44f, small)
        c.drawRect(0f, 62f, W.toFloat(), 66f, Paint().apply { color = NAVY })

        // Matriz
        val left = 24f
        val right = W - 24f
        val top = 74f
        val bottom = H - 24f
        val cw = (right - left) / 7f
        val first = ym.atDay(1)
        val offset = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val rows = (offset + ym.lengthOfMonth() + 6) / 7
        val headH = 22f
        val rh = (bottom - top - headH) / rows

        val names = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
        val hp = Paint().apply { color = NAVY }
        val ht = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        for (i in 0 until 7) {
            c.drawRect(left + i * cw, top, left + (i + 1) * cw, top + headH, hp)
            c.drawText(names[i], left + i * cw + cw / 2f, top + 15f, ht)
        }
        val line = Paint().apply { color = Color.parseColor("#AEB6D3"); strokeWidth = 0.8f; style = Paint.Style.STROKE }
        val dayP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY; textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
        val evP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A"); textSize = 8.5f }
        val chip = Paint().apply { color = SOFT }
        val moreP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B71C1C"); textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD }
        val today = LocalDate.now()

        for (r in 0 until rows) for (col in 0 until 7) {
            val idx = r * 7 + col
            val day = idx - offset + 1
            val x0 = left + col * cw
            val y0 = top + headH + r * rh
            val rect = RectF(x0, y0, x0 + cw, y0 + rh)
            val inMonth = day in 1..ym.lengthOfMonth()
            c.drawRect(rect, Paint().apply { color = if (!inMonth) Color.parseColor("#E4E4E4") else if (col >= 5) WEEKEND else Color.WHITE })
            c.drawRect(rect, line)
            if (!inMonth) continue
            if (ym.atDay(day) == today) {
                c.drawCircle(x0 + 15f, y0 + 14f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NAVY })
                c.drawText(day.toString(), x0 + 15f - dayP.measureText(day.toString()) / 2f, y0 + 19f,
                    Paint(dayP).apply { color = Color.WHITE })
            } else c.drawText(day.toString(), x0 + 7f, y0 + 19f, dayP)

            val evs = byDay[day].orEmpty()
            val lineH = 12f
            val avail = ((rh - 28f) / lineH).toInt().coerceAtLeast(1)
            val show = if (evs.size > avail) avail - 1 else evs.size
            for (i in 0 until show.coerceAtLeast(0)) {
                val e = evs[i]
                val yy = y0 + 28f + i * lineH
                c.drawRect(x0 + 3f, yy - 9f, x0 + cw - 3f, yy + 2f, chip)
                val label = (if (e.time.isBlank()) "" else e.time + " ") + e.name
                c.drawText(fit(label, evP, cw - 10f), x0 + 5f, yy, evP)
            }
            if (evs.size > show) {
                c.drawText("+${evs.size - show} más", x0 + 5f, y0 + 28f + show * lineH, moreP)
            }
        }
        c.drawRect(RectF(left, top, right, top + headH + rows * rh), Paint().apply {
            color = NAVY; style = Paint.Style.STROKE; strokeWidth = 1.5f
        })
    }
}

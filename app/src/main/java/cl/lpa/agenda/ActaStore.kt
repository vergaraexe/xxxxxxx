package cl.lpa.agenda

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una hoja del acta. kind:
 *   p1       primera hoja: encabezado, Motivo, Lugar y fecha + TEMAS TRATADOS
 *   temas    hoja adicional de Temas tratados
 *   acuerdos hoja de Acuerdos
 *   p2       participantes y firmas (siempre la ultima)
 *   p1old    primera hoja del formato anterior (solo para borradores viejos)
 */
class ActaSheet(val kind: String) {
    val strokes: SnapshotStateList<InkStroke> = mutableStateListOf()
    val images: SnapshotStateList<PagePhoto> = mutableStateListOf()
}

/** Copia inmutable de una hoja, para generar el PDF en segundo plano. */
data class ActaSnap(val kind: String, val strokes: List<InkStroke>, val images: List<PagePhoto>)

/**
 * Actas: completamente separadas de la agenda.
 *   files/actas/draft.json   -> acta en edicion (borrador); se guarda sola a cada trazo
 *   files/actas/img/         -> imagenes insertadas en el borrador
 *   files/actas/final/ (archivos PDF)  -> actas finalizadas y enviadas (para consulta)
 * Los trazos y las imagenes se guardan en puntos de hoja carta (612 x 792), asi se ven igual en cualquier pantalla y en el PDF.
 * El acta nueva parte con 3 hojas (Temas tratados, Acuerdos y Participantes); se pueden agregar mas hojas de
 * Temas o de Acuerdos, y las firmas siempre quedan en la ultima hoja.
 */
class ActaStore(context: Context) {
    companion object {
        const val PAGE_W = 612f      // carta en puntos
        const val PAGE_H = 792f
        const val K_P1 = "p1"
        const val K_P1_OLD = "p1old"
        const val K_P2 = "p2"
        const val K_TEMAS = "temas"
        const val K_ACUERDOS = "acuerdos"

        fun titleOf(kind: String) = when (kind) {
            K_P1 -> "Temas tratados · hoja 1"
            K_TEMAS -> "Temas tratados · hoja adicional"
            K_ACUERDOS -> "Acuerdos"
            K_P2 -> "Participantes y firmas"
            else -> "Acta de reunión"
        }

        /** Las imagenes se pueden insertar solo en las hojas de Temas tratados. */
        fun acceptsImages(kind: String) = kind == K_P1 || kind == K_TEMAS

        // Posicion (en puntos de hoja carta) del texto automatico "Fecha y hora", dentro del recuadro "Lugar y Fecha"
        const val STAMP_X = 556f
        const val STAMP_Y = 114f

        private fun meta(ctx: Context) = ctx.getSharedPreferences("acta_meta", Context.MODE_PRIVATE)

        /** Texto automatico "Fecha y hora" de la hoja 1: el momento en que se empezo a escribir el acta. */
        fun stampText(ctx: Context): String {
            val saved = meta(ctx).getLong("started", 0L)
            val ms = if (saved > 0L) saved else System.currentTimeMillis()
            return "Fecha y hora: " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "CL")).format(Date(ms))
        }

        fun blank(): List<ActaSheet> = listOf(ActaSheet(K_P1), ActaSheet(K_ACUERDOS), ActaSheet(K_P2))
    }

    private val appContext = context.applicationContext
    private val root = File(context.filesDir, "actas").apply { mkdirs() }
    private val draftFile = File(root, "draft.json")
    private val finalDir = File(root, "final").apply { mkdirs() }
    private val imgDir = File(root, "img").apply { mkdirs() }

    // ---------- imagenes ----------
    fun imageFile(name: String): File = File(imgDir, name)

    fun newImageFile(): File {
        imgDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        return File(imgDir, "acta_$stamp.jpg")
    }

    /** Reduce la imagen (maximo 1800 px), corrige su orientacion y la deja como JPEG. */
    fun normalizeImage(f: File) {
        val bmp = ImageUtil.load(f, 1800) ?: throw IllegalStateException("El archivo elegido no es una imagen valida.")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    /** Copia una imagen desde otra app (galeria, archivos, portapapeles) a la carpeta del acta. */
    fun importImage(uri: Uri): File {
        val dest = newImageFile()
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("No se pudo leer la imagen.")
            input.use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
            normalizeImage(dest)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        return dest
    }

    fun deleteImages(names: List<String>) {
        names.forEach { File(imgDir, it).delete() }
    }

    // ---------- borrador ----------
    private fun readStrokes(arr: JSONArray, into: MutableList<InkStroke>) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pts = o.getJSONArray("p")
            into.add(
                InkStroke(
                    points = List(pts.length() / 2) { k ->
                        Offset(pts.getDouble(2 * k).toFloat(), pts.getDouble(2 * k + 1).toFloat())
                    },
                    color = Color(o.getInt("c")),
                    width = o.getDouble("w").toFloat(),
                    highlighter = o.getBoolean("h"),
                    dashed = o.optBoolean("d", false)
                )
            )
        }
    }

    private fun readImages(arr: JSONArray?, into: MutableList<PagePhoto>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("f")
            if (!File(imgDir, name).exists()) continue
            into.add(
                PagePhoto(
                    name, o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.getDouble("w").toFloat(), o.getDouble("h").toFloat(),
                    o.optDouble("cl", 0.0).toFloat(), o.optDouble("ct", 0.0).toFloat(),
                    o.optDouble("cr", 1.0).toFloat(), o.optDouble("cb", 1.0).toFloat()
                )
            )
        }
    }

    fun loadDraft(): List<ActaSheet> {
        if (!draftFile.exists()) return blank()
        return try {
            val rootObj = JSONObject(draftFile.readText())
            val version = rootObj.optInt("v", 1)
            val sheets = rootObj.optJSONArray("sheets")
            val result = ArrayList<ActaSheet>()
            if (sheets != null) {
                for (i in 0 until sheets.length()) {
                    val o = sheets.getJSONObject(i)
                    var kind = o.getString("k")
                    // borradores de la version 1.6: su hoja 1 era la combinada (Temas + Acuerdos)
                    if (version < 2 && kind == K_P1) kind = K_P1_OLD
                    val sh = ActaSheet(kind)
                    readStrokes(o.getJSONArray("s"), sh.strokes)
                    readImages(o.optJSONArray("i"), sh.images)
                    result.add(sh)
                }
            } else {
                // formato de la version 1.5: solo 2 hojas
                val pages = rootObj.getJSONArray("pages")
                for (p in 0 until minOf(2, pages.length())) {
                    val sh = ActaSheet(if (p == 0) K_P1_OLD else K_P2)
                    readStrokes(pages.getJSONArray(p), sh.strokes)
                    result.add(sh)
                }
            }
            val firstOk = result.firstOrNull()?.kind.let { it == K_P1 || it == K_P1_OLD }
            if (result.size < 2 || !firstOk || result.last().kind != K_P2) blank() else result
        } catch (e: Exception) {
            blank() // borrador danado: se parte en blanco
        }
    }

    fun saveDraft(sheets: List<ActaSheet>) {
        if (sheets.any { it.strokes.isNotEmpty() || it.images.isNotEmpty() } && meta(appContext).getLong("started", 0L) == 0L) {
            meta(appContext).edit().putLong("started", System.currentTimeMillis()).apply()
        }
        val all = JSONArray()
        sheets.forEach { sh ->
            val arr = JSONArray()
            sh.strokes.toList().forEach { s ->
                val pts = JSONArray()
                s.points.forEach { pts.put(it.x.toDouble()); pts.put(it.y.toDouble()) }
                arr.put(
                    JSONObject().put("c", s.color.toArgb()).put("w", s.width.toDouble())
                        .put("h", s.highlighter).put("d", s.dashed).put("p", pts)
                )
            }
            val imgs = JSONArray()
            sh.images.toList().forEach {
                imgs.put(
                    JSONObject().put("f", it.file).put("x", it.x.toDouble()).put("y", it.y.toDouble())
                        .put("w", it.w.toDouble()).put("h", it.h.toDouble())
                        .put("cl", it.cl.toDouble()).put("ct", it.ct.toDouble())
                        .put("cr", it.cr.toDouble()).put("cb", it.cb.toDouble())
                )
            }
            all.put(JSONObject().put("k", sh.kind).put("s", arr).put("i", imgs))
        }
        val tmp = File(root, "draft.tmp")
        tmp.writeText(JSONObject().put("v", 2).put("sheets", all).toString())
        draftFile.delete()
        tmp.renameTo(draftFile)
    }

    /** Borra el borrador y las imagenes que habia insertado. */
    fun clearDraft() {
        meta(appContext).edit().remove("started").apply()
        draftFile.delete()
        imgDir.listFiles()?.forEach { it.delete() }
    }

    /** Guarda el PDF finalizado en la carpeta de consulta y devuelve ese archivo. */
    fun addFinal(pdf: File): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val dest = File(finalDir, "Acta_$stamp.pdf")
        pdf.copyTo(dest, overwrite = true)
        return dest
    }

    fun listFinal(): List<File> =
        finalDir.listFiles { f -> f.extension.equals("pdf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
}

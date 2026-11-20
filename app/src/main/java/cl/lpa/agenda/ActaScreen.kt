package cl.lpa.agenda

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Origen de una imagen insertada en el acta. */
enum class ImageSource { CAMERA, PASTE, FILE }

/**
 * Modulo de actas: muestra SOLO las hojas del acta (formato original, hojas carta) en el area principal.
 * Se escribe y se firma con el lapiz usando las mismas herramientas de la barra lateral.
 */
@Composable
fun ActaScreen(
    modifier: Modifier,
    sheets: List<ActaSheet>,
    tool: Tool,
    penColor: Color,
    hlColor: Color,
    penWidth: Float,
    shapesOn: Boolean,
    dashed: Boolean,
    onChanged: (Int) -> Unit,
    onNew: () -> Unit,
    onShowFinal: () -> Unit,
    onAddSheetMenu: () -> Unit,
    onAddSheet: (String) -> Unit,
    onRemoveSheet: (Int) -> Unit,
    onAddImage: (Int, ImageSource) -> Unit,
    onRequestFinalize: () -> Unit
) {
    Column(modifier.background(Color(0xFFE9E6DF))) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            sheets.forEachIndexed { i, sheet ->
                val k = sheet.kind
                val p2Count = sheets.count { it.kind == ActaStore.K_P2 }
                val showRow = k != ActaStore.K_P1_OLD
                if (showRow) {
                    Row(
                        Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "📄 ${ActaStore.titleOf(k)}",
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E2A5A),
                            modifier = Modifier.weight(1f)
                        )
                        if (ActaStore.acceptsImages(k)) {
                            DialogButton("📷 Foto") { onAddImage(i, ImageSource.CAMERA) }
                            DialogButton("📋 Pegar") { onAddImage(i, ImageSource.PASTE) }
                            DialogButton("🖼️ Archivo") { onAddImage(i, ImageSource.FILE) }
                        }
                        if (k == ActaStore.K_TEMAS || k == ActaStore.K_ACUERDOS || (k == ActaStore.K_P2 && p2Count > 1)) {
                            DialogButton("🗑️ Quitar hoja") { onRemoveSheet(i) }
                        }
                    }
                }
                key(sheet) {
                    ActaPage(
                        strokes = sheet.strokes, images = sheet.images, kind = k,
                        tool = tool, penColor = penColor, hlColor = hlColor,
                        penWidth = penWidth, shapesOn = shapesOn, dashed = dashed,
                        onChanged = { onChanged(i) }
                    )
                }
            }
            // Final del acta: aqui (lejos de donde se escribe) estan las acciones delicadas
            Column(
                Modifier
                    .widthIn(max = 900.dp).fillMaxWidth()
                    .background(Color(0xFFF7F5EE), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Fin del acta", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E2A5A))
                Text(
                    "¿Falta espacio? Agrega otra hoja. Temas y Acuerdos quedan antes de las firmas; Participantes queda al final.",
                    fontSize = 14.sp, color = Color(0xFF555555)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("＋ Hoja de Temas tratados") { onAddSheet(ActaStore.K_TEMAS) }
                    DialogButton("＋ Hoja de Acuerdos") { onAddSheet(ActaStore.K_ACUERDOS) }
                    DialogButton("＋ Hoja de Participantes") { onAddSheet(ActaStore.K_P2) }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD0CDC2)))
                Text(
                    "Cuando el acta esté completa y firmada, finalízala. Se pedirá confirmar antes de enviar.",
                    fontSize = 14.sp, color = Color(0xFF555555)
                )
                DialogButton("✅ Finalizar y enviar acta…", primary = true) { onRequestFinalize() }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFFF1EFE8)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "📋 Acta de reunión · se guarda sola",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E2A5A)
            )
            Spacer(Modifier.weight(1f))
            DialogButton("＋ Hoja adicional") { onAddSheetMenu() }
            DialogButton("＋ Nueva acta") { onNew() }
            DialogButton("📂 Actas finalizadas") { onShowFinal() }
        }
    }
}

@Composable
private fun ActaPage(
    strokes: MutableList<InkStroke>,
    images: MutableList<PagePhoto>,
    kind: String,
    tool: Tool,
    penColor: Color,
    hlColor: Color,
    penWidth: Float,
    shapesOn: Boolean,
    dashed: Boolean,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ActaStore(context) }
    val background = remember(kind) { ActaAssets.load(context, kind)?.asImageBitmap() }
    val stamp = remember(kind) { ActaStore.stampText(context) }
    val thumbs = remember { HashMap<String, ImageBitmap?>() }
    val imagesOn = ActaStore.acceptsImages(kind)
    val current = remember { mutableStateListOf<Offset>() }
    val haptic = LocalHapticFeedback.current
    val dens = LocalDensity.current.density
    val pageW = ActaStore.PAGE_W
    val pageH = ActaStore.PAGE_H
    var selected by remember { mutableStateOf<String?>(null) }
    var cropFile by remember { mutableStateOf<String?>(null) }
    var boxPx by remember { mutableFloatStateOf(0f) }
    // dp por punto de hoja (para ubicar la barra de la imagen seleccionada)
    val dpPerPt = if (boxPx > 0f) boxPx / dens / pageW else 1f

    fun thumbOf(file: String): ImageBitmap? =
        thumbs.getOrPut(file) { ImageUtil.load(store.imageFile(file), 1400)?.asImageBitmap() }

    Box(
        Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .aspectRatio(pageW / pageH)
            .onSizeChanged { boxPx = it.width.toFloat() }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .border(1.dp, Color(0xFFBBBBBB))
                .pointerInput(strokes, images, tool, penColor, hlColor, penWidth, shapesOn, dashed, selected) {
                    val pxPerPt = size.width / pageW            // pixeles por punto de hoja
                    val ptPerDp = pageW / (size.width / density) // puntos de hoja por dp
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // ---- DEDO: maneja las imagenes (o desplaza la hoja) ----
                        if (down.type == PointerType.Touch) {
                            if (!imagesOn || down.isConsumed) return@awaitEachGesture
                            val pt = down.position / pxPerPt
                            val selIdx = images.indexOfFirst { it.file == selected }

                            if (selIdx >= 0) {
                                val sel = images[selIdx]
                                // a) tirador de tamano (esquina inferior derecha)
                                if ((pt - Offset(sel.x + sel.w, sel.y + sel.h)).getDistance() < 26f) {
                                    down.consume()
                                    var changed = false
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val delta = (ch.position - ch.previousPosition) / pxPerPt
                                        val im = images[selIdx]
                                        val maxW = minOf(pageW - im.x, (pageH - im.y) * im.w / im.h)
                                        val nw = (im.w + (delta.x + delta.y * im.w / im.h) / 2f)
                                            .coerceIn(30f, maxOf(30f, maxW))
                                        images[selIdx] = im.copy(w = nw, h = nw * im.h / im.w)
                                        changed = true
                                        ch.consume()
                                    }
                                    if (changed) onChanged()
                                    return@awaitEachGesture
                                }
                                // b) arrastrar la imagen seleccionada para moverla
                                if (pt.x in sel.x..(sel.x + sel.w) && pt.y in sel.y..(sel.y + sel.h)) {
                                    down.consume()
                                    var moved = false
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        val delta = (ch.position - ch.previousPosition) / pxPerPt
                                        if (delta != Offset.Zero) {
                                            val im = images[selIdx]
                                            images[selIdx] = im.copy(
                                                x = (im.x + delta.x).coerceIn(0f, maxOf(0f, pageW - im.w)),
                                                y = (im.y + delta.y).coerceIn(0f, maxOf(0f, pageH - im.h))
                                            )
                                            moved = true
                                        }
                                        ch.consume()
                                    }
                                    if (moved) onChanged()
                                    return@awaitEachGesture
                                }
                            }

                            // c) toque simple: seleccionar una imagen (o quitar la seleccion).
                            //    Si el dedo se arrastra, no se consume: la hoja se desplaza normalmente.
                            val idx = images.indexOfLast {
                                pt.x in it.x..(it.x + it.w) && pt.y in it.y..(it.y + it.h)
                            }
                            var slipped = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    if (!slipped) selected = if (idx >= 0) images[idx].file else null
                                    break
                                }
                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    slipped = true
                                    break
                                }
                            }
                            return@awaitEachGesture
                        }

                        // ---- LAPIZ ----
                        if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture

                        val erasing = tool == Tool.ERASER || down.type == PointerType.Eraser
                        var erased = false

                        fun erase(p: Offset) {
                            val r = ERASER_RADIUS * ptPerDp
                            if (strokes.removeAll { s -> s.points.any { (it - p).getDistance() < r } }) erased = true
                        }

                        down.consume()
                        val start = down.position / pxPerPt
                        if (erasing) erase(start) else { current.clear(); current.add(start) }

                        var anchor = start
                        var anchorTime = SystemClock.uptimeMillis()
                        var snapped = false
                        while (true) {
                            val event = withTimeoutOrNull(120L) { awaitPointerEvent() }
                            if (event != null) {
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val p = change.position / pxPerPt
                                if (erasing) erase(p) else if (!snapped) current.add(p)
                                if ((p - anchor).getDistance() > HOLD_SLOP * ptPerDp) {
                                    anchor = p
                                    anchorTime = SystemClock.uptimeMillis()
                                }
                                change.consume()
                            }
                            if (shapesOn && !erasing && !snapped && current.size >= 10 &&
                                SystemClock.uptimeMillis() - anchorTime >= HOLD_MS
                            ) {
                                val shape = ShapeRecognizer.recognize(
                                    current.map { it / ptPerDp }, linesOnly = tool == Tool.HIGHLIGHTER
                                )
                                if (shape != null) {
                                    current.clear()
                                    current.addAll(shape.map { it * ptPerDp })
                                    snapped = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else {
                                    anchorTime = SystemClock.uptimeMillis()
                                }
                            }
                        }

                        if (erasing) {
                            if (erased) onChanged()
                        } else if (current.isNotEmpty()) {
                            val hl = tool == Tool.HIGHLIGHTER
                            strokes.add(
                                InkStroke(
                                    current.toList(),
                                    if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                                    (if (hl) HL_WIDTH else penWidth) * ptPerDp,
                                    hl,
                                    dashed && !hl
                                )
                            )
                            current.clear()
                            onChanged()
                        }
                    }
                }
        ) {
            val sc = size.width / pageW   // pixeles por punto
            if (background != null) {
                drawImage(
                    background,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.High
                )
            }
            // "Fecha y hora" automatica junto a "Lugar y Fecha" (solo hoja 1)
            if (kind == ActaStore.K_P1) {
                drawIntoCanvas { cv ->
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(70, 80, 120)
                        textSize = 9f * sc
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    cv.nativeCanvas.drawText(stamp, ActaStore.STAMP_X * sc, ActaStore.STAMP_Y * sc, p)
                }
            }
            // imagenes insertadas (debajo de lo escrito)
            for (im in images) {
                val bmp = thumbOf(im.file) ?: continue
                val tl = Offset(im.x * sc, im.y * sc)
                drawImage(
                    bmp,
                    srcOffset = IntOffset((im.cl * bmp.width).roundToInt(), (im.ct * bmp.height).roundToInt()),
                    srcSize = IntSize(
                        ((im.cr - im.cl) * bmp.width).roundToInt().coerceAtLeast(1),
                        ((im.cb - im.ct) * bmp.height).roundToInt().coerceAtLeast(1)
                    ),
                    dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                    dstSize = IntSize((im.w * sc).roundToInt(), (im.h * sc).roundToInt()),
                    filterQuality = FilterQuality.Medium
                )
                drawRect(
                    if (im.file == selected) Color(0xFF1E88E5) else Color(0xFF888888),
                    tl, Size(im.w * sc, im.h * sc),
                    style = DrawStroke(if (im.file == selected) 2.5f * dens else 1f)
                )
                if (im.file == selected) {
                    val corner = Offset((im.x + im.w) * sc, (im.y + im.h) * sc)
                    drawCircle(Color(0xFF1E88E5), 13f * dens, corner)
                    drawCircle(Color.White, 8f * dens, corner)
                }
            }
            strokes.filter { it.highlighter }.forEach { drawInk(it, sc) }
            strokes.filter { !it.highlighter }.forEach { drawInk(it, sc) }
            if (current.isNotEmpty() && tool != Tool.ERASER) {
                val hl = tool == Tool.HIGHLIGHTER
                val ptPerDp = pageW / (size.width / density)
                drawInk(
                    InkStroke(
                        current.toList(),
                        if (hl) hlColor.copy(alpha = 0.4f) else penColor,
                        (if (hl) HL_WIDTH else penWidth) * ptPerDp,
                        hl,
                        dashed && !hl
                    ), sc
                )
            }
        }

        // Barra de la imagen seleccionada: recortar, quitar, listo
        selected?.let { f ->
            images.firstOrNull { it.file == f }?.let { im ->
                val tx: Dp = (im.x * dpPerPt).dp
                val top = im.y * dpPerPt
                val ty: Dp = if (top > 56f) (top - 56f).dp else ((im.y + im.h) * dpPerPt + 12f).dp
                Row(
                    Modifier
                        .offset(x = tx, y = ty)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFFBBBBBB), RoundedCornerShape(10.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DialogButton("✂️ Recortar") { cropFile = f }
                    DialogButton("🗑️ Quitar") {
                        images.removeAll { it.file == f }
                        store.deleteImages(listOf(f))
                        thumbs.remove(f)
                        selected = null
                        onChanged()
                    }
                    DialogButton("✔ Listo", primary = true) { selected = null }
                }
            }
        }
    }

    cropFile?.let { f ->
        val bmp = remember(f) { ImageUtil.load(store.imageFile(f), 1400) }
        val cur = images.firstOrNull { it.file == f }
        if (bmp != null && cur != null) {
            CropDialog(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                initial = listOf(cur.cl, cur.ct, cur.cr, cur.cb),
                onApply = { l, t, r, b ->
                    val i = images.indexOfFirst { it.file == f }
                    if (i >= 0) {
                        val im = images[i]
                        var nw = im.w
                        var nh = im.w * ((b - t) * bmp.height) / ((r - l) * bmp.width)
                        val k = minOf(1f, pageW / nw, pageH / nh)
                        nw *= k
                        nh *= k
                        images[i] = im.copy(
                            w = nw, h = nh, cl = l, ct = t, cr = r, cb = b,
                            x = im.x.coerceIn(0f, maxOf(0f, pageW - nw)),
                            y = im.y.coerceIn(0f, maxOf(0f, pageH - nh))
                        )
                        onChanged()
                    }
                    cropFile = null
                },
                onCancel = { cropFile = null }
            )
        }
    }
}

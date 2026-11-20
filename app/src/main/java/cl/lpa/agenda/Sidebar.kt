package cl.lpa.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// Colores rapidos visibles en la barra (el 4to boton abre la paleta completa)
private val quickPen = listOf(Color(0xFF111111), Color(0xFF1565C0), Color(0xFFD32F2F))   // negro, azul, rojo
private val quickHl = listOf(Color(0xFFFFEB3B), Color(0xFF76FF03), Color(0xFFFF80AB))    // amarillo, verde, rosado

/** Paleta completa del lapiz: fila de grises + 4 filas de tonos (oscuros a claros). */
private val fullPenPalette: List<Color> = buildList {
    for (i in 0 until 12) add(Color.hsv(0f, 0f, 0.05f + i * 0.08f))
    val rows = listOf(1f to 0.55f, 1f to 0.85f, 0.55f to 1f, 0.28f to 1f)
    rows.forEach { (s, v) -> for (h in 0 until 12) add(Color.hsv(h * 30f, s, v)) }
}

/** Paleta del destacador: tonos vivos y pasteles. */
private val fullHlPalette: List<Color> = buildList {
    val rows = listOf(0.95f to 1f, 0.6f to 1f, 0.3f to 1f)
    rows.forEach { (s, v) -> for (h in 0 until 12) add(Color.hsv(h * 30f, s, v)) }
}

// ---------- Barra lateral: UNA columna, angosta ----------
@Composable
fun Sidebar(
    tool: Tool, penColor: Color, hlColor: Color, penWidth: Float, photoCount: Int,
    dashedOn: Boolean,
    module: String, onModule: (String) -> Unit,
    onTool: (Tool) -> Unit, onPenColor: (Color) -> Unit, onPenWidth: (Float) -> Unit, onHlColor: (Color) -> Unit,
    onUndo: () -> Unit, onClear: () -> Unit,
    onPhoto: () -> Unit, onEvidence: () -> Unit, onSettings: () -> Unit,
    onDashed: () -> Unit,
    calcOpen: Boolean, onCalc: () -> Unit,
    shortcutLabels: List<String?>, openShortcuts: Set<Int>, onShortcut: (Int) -> Unit,
    // navegacion por dias y hojas (esquina inferior izquierda; solo en la agenda)
    page: Int, pageCount: Int, isToday: Boolean, editable: Boolean,
    onPrevDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit,
    onPrevPage: () -> Unit, onNextPage: () -> Unit, onNewPage: () -> Unit
) {
    var palette by remember { mutableStateOf<String?>(null) } // "pen" | "hl" | null

    Column(Modifier.width(90.dp).fillMaxHeight().background(navy)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ToolButton("📅", "Agenda", module == "agenda") { onModule("agenda") }
            ToolButton("📋", "Acta", module == "acta") { onModule("acta") }
            ToolButton("🗓️", "Calendario", module == "calendar") { onModule("calendar") }

            if (module != "calendar") {
            ToolButton("✏️", "Lápiz", tool == Tool.PEN) { onTool(Tool.PEN) }
            QuickColors(quickPen, penColor, tool == Tool.PEN, onPenColor) { palette = "pen" }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WidthButton(penWidths[0], penWidths[0] == penWidth && tool == Tool.PEN, penColor) { onPenWidth(penWidths[0]) }
                WidthButton(penWidths[1], penWidths[1] == penWidth && tool == Tool.PEN, penColor) { onPenWidth(penWidths[1]) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WidthButton(penWidths[2], penWidths[2] == penWidth && tool == Tool.PEN, penColor) { onPenWidth(penWidths[2]) }
                WidthButton(penWidths[3], penWidths[3] == penWidth && tool == Tool.PEN, penColor) { onPenWidth(penWidths[3]) }
            }
            ToolButton("┈┈", "Punteado", dashedOn, onDashed)

            ToolButton("🖍️", "Destacar", tool == Tool.HIGHLIGHTER) { onTool(Tool.HIGHLIGHTER) }
            QuickColors(quickHl, hlColor, tool == Tool.HIGHLIGHTER, onHlColor) { palette = "hl" }

            ToolButton("🧽", "Goma", tool == Tool.ERASER) { onTool(Tool.ERASER) }
            ToolButton("↩️", "Deshacer", false, onUndo)
            ToolButton("🗑️", "Limpiar", false, onClear)
            }
            ToolButton("🧮", "Calculadora", calcOpen, onCalc)
            shortcutLabels.forEachIndexed { i, label ->
                ToolButton("📄", label ?: "Archivo ${i + 1}", (i + 1) in openShortcuts) { onShortcut(i + 1) }
            }
            if (module == "agenda") {
                ToolButton("📷", "Foto", false, onPhoto)
                ToolButton("🖼️", "Fotos ($photoCount)", false, onEvidence)
            }
            ToolButton("⚙️", "Ajustes", false, onSettings)
        }

        // Esquina inferior izquierda: dias anteriores y hojas del dia
        if (module == "agenda") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x33000000))
                    // margen inferior: asi los botones quedan arriba del borde de la tablet y se pueden tocar
                    .navigationBarsPadding()
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 84.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Día", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NavMini("◀", true, onPrevDay)
                    NavMini("▶", !isToday, onNextDay)
                }
                if (!isToday) NavWide("Hoy", true, onToday)
                Text("Hoja $page de $pageCount", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NavMini("◀", page > 1, onPrevPage)
                    NavMini("▶", page < pageCount, onNextPage)
                }
                NavWide("＋ Hoja", editable, onNewPage)
            }
        }
    }

    when (palette) {
        "pen" -> ColorPickerDialog("Color del lápiz", fullPenPalette, penColor, { onPenColor(it); palette = null }) { palette = null }
        "hl" -> ColorPickerDialog("Color del destacador", fullHlPalette, hlColor, { onHlColor(it); palette = null }) { palette = null }
    }
}

/** 3 colores visibles + 4to boton (paleta completa). Si el color actual no es uno de los 3, el 4to lo muestra. */
@Composable
private fun QuickColors(
    quick: List<Color>, selected: Color, active: Boolean,
    onPick: (Color) -> Unit, onMore: () -> Unit
) {
    val custom = quick.none { it == selected }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(quick[0], quick[0] == selected && active) { onPick(quick[0]) }
            ColorDot(quick[1], quick[1] == selected && active) { onPick(quick[1]) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorDot(quick[2], quick[2] == selected && active) { onPick(quick[2]) }
            // 4to boton: abre la paleta; si hay un color personalizado, se ve aqui
            Box(
                Modifier
                    .size(30.dp).clip(CircleShape)
                    .background(if (custom) selected else Color.White)
                    .border(if (custom && active) 3.dp else 1.dp, if (custom && active) Color.White else Color(0x88FFFFFF), CircleShape)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center
            ) {
                Text("🎨", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(
    title: String, colors: List<Color>, selected: Color,
    onPick: (Color) -> Unit, onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Toca un color para usarlo.", fontSize = 14.sp, color = Color(0xFF555555))
                colors.chunked(12).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier
                                    .size(34.dp).clip(CircleShape).background(c)
                                    .border(
                                        if (c == selected) 3.dp else 1.dp,
                                        if (c == selected) navy else Color(0x33000000), CircleShape
                                    )
                                    .clickable { onPick(c) }
                            )
                        }
                    }
                }
                DialogButton("Cerrar") { onClose() }
            }
        }
    }
}

@Composable
private fun NavMini(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 16.sp, color = if (enabled) Color.White else Color(0x55FFFFFF),
        modifier = Modifier
            .width(35.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0x44FFFFFF) else Color(0x14FFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun NavWide(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, color = if (enabled) Color.White else Color(0x55FFFFFF),
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0x44FFFFFF) else Color(0x14FFFFFF))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun ToolButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x44FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 22.sp, color = Color.White)
        Text(label, fontSize = 10.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WidthButton(width: Float, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(35.dp).height(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color(0x55FFFFFF) else Color(0x22FFFFFF))
            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Muestra del trazo con el grosor real; si el lapiz es oscuro se dibuja en blanco para que se vea
        Box(
            Modifier
                .width(22.dp).height(width.dp)
                .clip(RoundedCornerShape(50))
                .background(if (color == penColors[0]) Color.White else color)
        )
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x88FFFFFF), CircleShape)
            .clickable(onClick = onClick)
    )
}

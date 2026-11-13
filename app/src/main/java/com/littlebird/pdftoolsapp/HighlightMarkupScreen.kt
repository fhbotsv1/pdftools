package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val MarkupBackground = Color(0xFF101522)
private val MarkupSurface = Color(0xFF1B2433)
private val MarkupAccent = Color(0xFFEF6262)
private val MarkupMuted = Color(0xFF9AA9BD)

private data class MarkupPoint(val x: Float, val y: Float)
private data class PdfMarkup(
    val page: Int,
    val tool: String,
    val points: List<MarkupPoint>,
    val color: Int,
    val width: Float,
    val note: String = ""
)
private data class MarkupPreview(val bitmap: Bitmap, val pageWidth: Float, val pageHeight: Float, val pageCount: Int)

@Composable
fun HighlightMarkupScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<MarkupPreview?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var selectedTool by remember { mutableStateOf("Highlight") }
    var selectedColor by remember { mutableIntStateOf(0xFFFFE45C.toInt()) }
    var lineWidth by remember { mutableFloatStateOf(3f) }
    var marks by remember { mutableStateOf(listOf<PdfMarkup>()) }
    var activePoints by remember { mutableStateOf(listOf<MarkupPoint>()) }
    var pendingComment by remember { mutableStateOf<MarkupPoint?>(null) }
    var commentText by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { previewFile?.delete() } }

    fun loadPage(file: File, pageIndex: Int) {
        scope.launch {
            isWorking = true
            try {
                preview = withContext(Dispatchers.IO) { renderMarkupPreview(file, pageIndex) }
                status = null
            } catch (error: Exception) {
                status = "Could not preview page: " + (error.localizedMessage ?: "Unsupported PDF")
            } finally { isWorking = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Loading PDF preview…"
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) { }
                    val prepared = withContext(Dispatchers.IO) {
                        val file = File(context.cacheDir, "markup_preview_" + System.currentTimeMillis() + ".pdf")
                        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected PDF.")
                        input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                        Pair(file, renderMarkupPreview(file, 0))
                    }
                    previewFile?.delete()
                    previewFile = prepared.first
                    sourceUri = uri
                    sourceName = getMarkupDisplayName(context, uri)
                    preview = prepared.second
                    pageCount = prepared.second.pageCount
                    selectedPage = 0
                    marks = emptyList()
                    activePoints = emptyList()
                    status = "Choose a tool, then draw directly on the page."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    preview = null
                    status = "Could not read PDF: " + (error.localizedMessage ?: "Check that it is valid and unlocked.")
                } finally { isWorking = false }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val inputUri = sourceUri
        if (destination != null && inputUri != null) {
            val savedMarks = marks.toList()
            scope.launch {
                isWorking = true
                status = "Applying markup and saving…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(inputUri) ?: error("Could not open the selected PDF.")
                        val bytes = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                if (document.isEncrypted) error("Password-protected PDFs are not supported by this tool.")
                                savedMarks.filter { it.page in 0 until document.numberOfPages && it.tool == "Highlight" }
                                    .forEach { drawMarkup(document, document.getPage(it.page), it, true) }
                                savedMarks.filter { it.page in 0 until document.numberOfPages && it.tool != "Highlight" }
                                    .forEach { drawMarkup(document, document.getPage(it.page), it, false) }
                                ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
                            }
                        }
                        val output = context.contentResolver.openOutputStream(destination) ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Marked-up PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not save markup: " + (error.localizedMessage ?: "Check that the PDF is valid and writable.")
                } finally { isWorking = false }
            }
        } else if (destination == null) status = "Saving cancelled."
    }

    val tools = listOf("Highlight", "Underline", "Strikethrough", "Pen", "Arrow", "Rectangle", "Circle", "Comment")
    val colors = listOf(
        "Yellow" to 0xFFFFE45C.toInt(),
        "Green" to 0xFF69D98A.toInt(),
        "Pink" to 0xFFFF80AB.toInt(),
        "Blue" to 0xFF64B5F6.toInt(),
        "Red" to 0xFFE85D5D.toInt(),
        "Black" to 0xFF202020.toInt()
    )

    MaterialTheme(colorScheme = darkColorScheme(primary = MarkupAccent, surface = MarkupSurface)) {
        Column(modifier.fillMaxSize().background(MarkupBackground).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("Highlight & Markup", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Mark up pages and save a new PDF", color = MarkupMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MarkupSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("PDF page", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !isWorking, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MarkupAccent), shape = RoundedCornerShape(12.dp)) {
                            Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        if (sourceName != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(sourceName!!, color = MarkupMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (preview != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(onClick = { if (selectedPage > 0) { selectedPage--; previewFile?.let { loadPage(it, selectedPage) } } }, enabled = selectedPage > 0 && !isWorking) { Text("Previous") }
                                Text("Page " + (selectedPage + 1) + " of " + pageCount, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                OutlinedButton(onClick = { if (selectedPage < pageCount - 1) { selectedPage++; previewFile?.let { loadPage(it, selectedPage) } } }, enabled = selectedPage < pageCount - 1 && !isWorking) { Text("Next") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                                Image(preview!!.bitmap.asImageBitmap(), contentDescription = "PDF page preview", modifier = Modifier.fillMaxWidth().aspectRatio(preview!!.pageWidth / preview!!.pageHeight))
                                Canvas(
                                    Modifier.fillMaxWidth().aspectRatio(preview!!.pageWidth / preview!!.pageHeight)
                                        .pointerInput(selectedTool, selectedPage, selectedColor, lineWidth) {
                                            detectDragGestures(
                                                onDragStart = { point ->
                                                    val p = MarkupPoint((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
                                                    activePoints = listOf(p)
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val p = MarkupPoint((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                                                    activePoints = if (selectedTool == "Pen") activePoints + p else listOf(activePoints.firstOrNull() ?: p, p)
                                                },
                                                onDragEnd = {
                                                    if (activePoints.isNotEmpty() && selectedTool != "Comment") {
                                                        marks = marks + PdfMarkup(selectedPage, selectedTool, activePoints, selectedColor, lineWidth)
                                                    }
                                                    activePoints = emptyList()
                                                }
                                            )
                                        }
                                        .pointerInput(selectedTool, selectedPage) {
                                            detectTapGestures { point ->
                                                if (selectedTool == "Comment") {
                                                    pendingComment = MarkupPoint((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
                                                    commentText = ""
                                                }
                                            }
                                        }
                                ) {
                                    val visible = marks.filter { it.page == selectedPage } +
                                        if (activePoints.isNotEmpty()) listOf(PdfMarkup(selectedPage, selectedTool, activePoints, selectedColor, lineWidth)) else emptyList()
                                    visible.forEach { mark -> drawMarkupPreview(mark, this.size.width, this.size.height) }
                                }
                            }
                            Text("Drag on the page to draw. Tap the page to place a comment.", color = MarkupMuted, fontSize = 11.sp)
                        } else {
                            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF263246)), contentAlignment = Alignment.Center) {
                                Text("Select a PDF to see its page preview", color = MarkupMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MarkupSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Markup tools", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        FlowRowCompat(tools, selectedTool) { selectedTool = it }
                        Spacer(Modifier.height(12.dp))
                        Text("Color", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            colors.chunked(3).forEach { colorRow ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    colorRow.forEach { item ->
                                        FilterChip(
                                            selected = selectedColor == item.second,
                                            onClick = { selectedColor = item.second },
                                            label = { Text(item.first, fontSize = 10.sp) },
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MarkupAccent, selectedLabelColor = Color.White)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Line width: " + lineWidth.toInt() + " pt", color = MarkupMuted, fontSize = 12.sp)
                        Slider(lineWidth, { lineWidth = it }, valueRange = 1f..12f, enabled = !isWorking, colors = SliderDefaults.colors(thumbColor = MarkupAccent, activeTrackColor = MarkupAccent))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(marks.size.toString() + " markup item(s)", color = MarkupMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Undo", color = if (marks.isNotEmpty()) MarkupAccent else MarkupMuted, modifier = Modifier.clickable(enabled = marks.isNotEmpty() && !isWorking) { marks = marks.dropLast(1) }.padding(4.dp))
                                Text("Clear all", color = if (marks.isNotEmpty()) MarkupAccent else MarkupMuted, modifier = Modifier.clickable(enabled = marks.isNotEmpty() && !isWorking) { marks = emptyList() }.padding(4.dp))
                            }
                        }
                    }
                }
                if (status != null) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF263246))) {
                        Text(status!!, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                Button(
                    onClick = { saveLauncher.launch((sourceName?.substringBeforeLast('.', sourceName ?: "document") ?: "document") + "_marked_up.pdf") },
                    enabled = sourceUri != null && marks.isNotEmpty() && !isWorking,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MarkupAccent),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (isWorking) "Please wait…" else "Save Marked-up PDF", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (pendingComment != null) {
        AlertDialog(
            onDismissRequest = { pendingComment = null },
            title = { Text("Add comment") },
            text = {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("Comment") },
                    placeholder = { Text("Enter a note for this location") },
                    singleLine = false
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val point = pendingComment
                    if (point != null && commentText.isNotBlank()) {
                        marks = marks + PdfMarkup(selectedPage, "Comment", listOf(point), selectedColor, lineWidth, commentText.trim())
                    }
                    pendingComment = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pendingComment = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FlowRowCompat(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(4).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { name ->
                    FilterChip(
                        selected = selected == name,
                        onClick = { onSelect(name) },
                        label = { Text(name, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MarkupAccent, selectedLabelColor = Color.White)
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkupPreview(mark: PdfMarkup, width: Float, height: Float) {
    val color = Color(mark.color)
    val pts = mark.points
    if (pts.isEmpty()) return
    val first = androidx.compose.ui.geometry.Offset(pts.first().x * width, pts.first().y * height)
    val last = androidx.compose.ui.geometry.Offset(pts.last().x * width, pts.last().y * height)
    val stroke = max(2f, mark.width * 1.4f)
    when (mark.tool) {
        "Highlight" -> drawRect(color.copy(alpha = 0.38f), topLeft = androidx.compose.ui.geometry.Offset(min(first.x, last.x), min(first.y, last.y)), size = androidx.compose.ui.geometry.Size(max(2f, abs(last.x - first.x)), max(3f, abs(last.y - first.y))))
        "Underline" -> drawLine(color, androidx.compose.ui.geometry.Offset(first.x, last.y), androidx.compose.ui.geometry.Offset(last.x, last.y), stroke)
        "Strikethrough" -> drawLine(color, androidx.compose.ui.geometry.Offset(first.x, (first.y + last.y) / 2f), androidx.compose.ui.geometry.Offset(last.x, (first.y + last.y) / 2f), stroke)
        "Pen" -> {
            val path = Path().apply { moveTo(first.x, first.y); pts.drop(1).forEach { lineTo(it.x * width, it.y * height) } }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        }
        "Arrow" -> {
            drawLine(color, first, last, stroke)
            val angle = kotlin.math.atan2(last.y - first.y, last.x - first.x)
            val head = 16f
            val left = androidx.compose.ui.geometry.Offset(last.x - head * kotlin.math.cos(angle - 0.5f), last.y - head * kotlin.math.sin(angle - 0.5f))
            val right = androidx.compose.ui.geometry.Offset(last.x - head * kotlin.math.cos(angle + 0.5f), last.y - head * kotlin.math.sin(angle + 0.5f))
            drawLine(color, last, left, stroke); drawLine(color, last, right, stroke)
        }
        "Rectangle" -> drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(min(first.x, last.x), min(first.y, last.y)), size = androidx.compose.ui.geometry.Size(max(2f, abs(last.x - first.x)), max(2f, abs(last.y - first.y))), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        "Circle" -> drawOval(color, topLeft = androidx.compose.ui.geometry.Offset(min(first.x, last.x), min(first.y, last.y)), size = androidx.compose.ui.geometry.Size(max(2f, abs(last.x - first.x)), max(2f, abs(last.y - first.y))), style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        "Comment" -> drawCircle(color, radius = 9f, center = first)
    }
}

private fun drawMarkup(document: PDDocument, page: PDPage, mark: PdfMarkup, behindText: Boolean) {
    if (mark.points.isEmpty()) return
    val box = page.mediaBox
    fun px(point: MarkupPoint) = point.x.coerceIn(0f, 1f) * box.width
    fun py(point: MarkupPoint) = (1f - point.y.coerceIn(0f, 1f)) * box.height
    val first = mark.points.first()
    val last = mark.points.last()
    val x1 = px(first); val y1 = py(first)
    val x2 = px(last); val y2 = py(last)
    val streamMode = if (behindText) PDPageContentStream.AppendMode.PREPEND else PDPageContentStream.AppendMode.APPEND
    PDPageContentStream(document, page, streamMode, true, true).use { stream ->
        stream.setLineWidth(mark.width.coerceIn(0.5f, 20f))
        val red = android.graphics.Color.red(mark.color)
        val green = android.graphics.Color.green(mark.color)
        val blue = android.graphics.Color.blue(mark.color)
        if (mark.tool == "Highlight") {
            stream.setNonStrokingColor(red, green, blue)
            stream.addRect(min(x1, x2), min(y1, y2), max(2f, abs(x2 - x1)), max(3f, abs(y2 - y1)))
            stream.fill()
        } else {
            stream.setStrokingColor(red, green, blue)
            when (mark.tool) {
                "Underline" -> { stream.moveTo(x1, y2); stream.lineTo(x2, y2); stream.stroke() }
                "Strikethrough" -> { val y = (y1 + y2) / 2f; stream.moveTo(x1, y); stream.lineTo(x2, y); stream.stroke() }
                "Pen" -> {
                    stream.moveTo(x1, y1)
                    mark.points.drop(1).forEach { stream.lineTo(px(it), py(it)) }
                    stream.stroke()
                }
                "Arrow" -> {
                    stream.moveTo(x1, y1); stream.lineTo(x2, y2); stream.stroke()
                    val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
                    val head = 12f
                    stream.moveTo(x2, y2)
                    stream.lineTo(x2 - head * kotlin.math.cos(angle - 0.5f).toFloat(), y2 - head * kotlin.math.sin(angle - 0.5f).toFloat())
                    stream.moveTo(x2, y2)
                    stream.lineTo(x2 - head * kotlin.math.cos(angle + 0.5f).toFloat(), y2 - head * kotlin.math.sin(angle + 0.5f).toFloat())
                    stream.stroke()
                }
                "Rectangle" -> { stream.addRect(min(x1, x2), min(y1, y2), max(2f, abs(x2 - x1)), max(2f, abs(y2 - y1))); stream.stroke() }
                "Circle" -> {
                    val left = min(x1, x2); val right = max(x1, x2)
                    val bottom = min(y1, y2); val top = max(y1, y2)
                    val k = 0.55228475f
                    val rx = max(1f, (right - left) / 2f); val ry = max(1f, (top - bottom) / 2f)
                    val cx = (left + right) / 2f; val cy = (bottom + top) / 2f
                    stream.moveTo(cx + rx, cy)
                    stream.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry)
                    stream.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy)
                    stream.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry)
                    stream.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
                    stream.closePath(); stream.stroke()
                }
                "Comment" -> {
                    stream.setNonStrokingColor(red, green, blue)
                    stream.addRect(x1 - 5f, y1 - 5f, 10f, 10f); stream.fill()
                    val safe = mark.note.replace(Regex("[^\\x20-\\x7E]"), "?").take(240)
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 9f)
                    stream.newLineAtOffset((x1 + 9f).coerceAtMost(box.width - 20f), (y1 - 3f).coerceAtLeast(10f))
                    stream.showText(safe)
                    stream.endText()
                }
            }
        }
    }
}

private fun renderMarkupPreview(file: File, pageIndex: Int): MarkupPreview {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(pageIndex in 0 until renderer.pageCount) { "Page number is out of range." }
            renderer.openPage(pageIndex).use { page ->
                val width = 1200
                val height = max(1, (width.toFloat() * page.height / page.width).toInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return MarkupPreview(bitmap, page.width.toFloat(), page.height.toFloat(), renderer.pageCount)
            }
        }
    }
}

private fun getMarkupDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

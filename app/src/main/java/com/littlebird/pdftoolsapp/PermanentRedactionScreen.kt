package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val RedactBackground = Color(0xFF101522)
private val RedactSurface = Color(0xFF1B2433)
private val RedactAccent = Color(0xFFEF6262)
private val RedactMuted = Color(0xFF9AA9BD)

private data class RedactionArea(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Composable
fun PermanentRedactionScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var pageAspect by remember { mutableFloatStateOf(0.72f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var redactions by remember { mutableStateOf<List<List<RedactionArea>>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(previewBitmap) {
        onDispose { previewBitmap?.recycle() }
    }

    suspend fun loadPreview(uri: Uri, index: Int) {
        val result = withContext(Dispatchers.IO) { renderRedactionPage(context, uri, index) }
        previewBitmap = result.first
        pageAspect = result.first.width.toFloat() / result.first.height.toFloat()
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val uri = sourceUri
        if (destination == null) {
            status = "Save cancelled."
        } else if (uri != null) {
            val areasSnapshot = redactions.map { it.toList() }
            scope.launch {
                isWorking = true
                status = "Applying permanent redactions and rebuilding PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { out ->
                            PDDocument().use { result ->
                                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                                    ?: error("Could not open the source PDF.")
                                descriptor.use { pfd ->
                                    PdfRenderer(pfd).use { renderer ->
                                        for (index in 0 until renderer.pageCount) {
                                            renderer.openPage(index).use { page ->
                                                val scale = min(2.0f, min(2600f / page.width, 3400f / page.height))
                                                    .coerceAtLeast(1.0f)
                                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                bitmap.eraseColor(AndroidColor.WHITE)
                                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                                val canvas = AndroidCanvas(bitmap)
                                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                    color = AndroidColor.BLACK
                                                    style = Paint.Style.FILL
                                                }
                                                areasSnapshot.getOrNull(index).orEmpty().forEach { area ->
                                                    canvas.drawRect(
                                                        RectF(
                                                            area.left * width,
                                                            area.top * height,
                                                            area.right * width,
                                                            area.bottom * height
                                                        ),
                                                        paint
                                                    )
                                                }
                                                val pageWidth = page.width.toFloat()
                                                val pageHeight = page.height.toFloat()
                                                val pdfPage = PDPage(PDRectangle(pageWidth, pageHeight))
                                                result.addPage(pdfPage)
                                                val image = JPEGFactory.createFromImage(result, bitmap, 0.96f)
                                                PDPageContentStream(result, pdfPage).use { stream ->
                                                    stream.drawImage(image, 0f, 0f, pageWidth, pageHeight)
                                                }
                                                bitmap.recycle()
                                            }
                                        }
                                    }
                                }
                                result.documentInformation.title = "Redacted document"
                                result.save(out)
                            }
                        }
                    }
                    status = "Redacted PDF saved. Redacted areas are flattened into the page image; the original PDF is unchanged."
                } catch (error: Exception) {
                    status = "Could not create redacted PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Loading PDF pages…"
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                    val count = withContext(Dispatchers.IO) {
                        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                            ?: error("Could not open the selected PDF.")
                        descriptor.use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
                    }
                    require(count > 0) { "The PDF has no pages." }
                    sourceUri = uri
                    sourceName = getRedactionPdfName(context, uri)
                    pageCount = count
                    selectedPage = 0
                    redactions = List(count) { emptyList() }
                    loadPreview(uri, 0)
                    status = "Drag on the page to mark each area for permanent redaction."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pageCount = 0
                    redactions = emptyList()
                    previewBitmap = null
                    status = "Could not open PDF: ${error.localizedMessage ?: "The file may be damaged or password-protected."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    fun changePage(index: Int) {
        val uri = sourceUri ?: return
        if (index !in 0 until pageCount || isWorking) return
        selectedPage = index
        dragStart = null
        dragCurrent = null
        scope.launch {
            isWorking = true
            try {
                loadPreview(uri, index)
                status = null
            } catch (error: Exception) {
                status = "Could not preview page: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                isWorking = false
            }
        }
    }

    val totalAreas = redactions.sumOf { it.size }

    MaterialTheme(colorScheme = darkColorScheme(primary = RedactAccent, surface = RedactSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(RedactBackground)
                .verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable(enabled = !isWorking) { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("Permanent Redaction", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Remove sensitive areas from a PDF", color = RedactMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = RedactSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RedactAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF",
                            color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(7.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages • $totalAreas redaction area(s)",
                            color = RedactAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Page preview", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Drag across text or content to mark it in black.",
                        color = RedactMuted, fontSize = 11.sp)
                }
                if (pageCount > 0) {
                    Text("Page ${selectedPage + 1}/$pageCount", color = RedactAccent,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            if (previewBitmap != null) {
                val bitmap = previewBitmap!!
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(pageAspect)
                        .background(Color.White, RoundedCornerShape(6.dp))
                ) {
                    Image(bitmap.asImageBitmap(), contentDescription = "PDF page preview",
                        modifier = Modifier.fillMaxSize())
                    Canvas(
                        modifier = Modifier.fillMaxSize().pointerInput(selectedPage, redactions, bitmap) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    dragStart = Offset(
                                        (offset.x / size.width).coerceIn(0f, 1f),
                                        (offset.y / size.height).coerceIn(0f, 1f)
                                    )
                                    dragCurrent = dragStart
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    dragCurrent = Offset(
                                        (change.position.x / size.width).coerceIn(0f, 1f),
                                        (change.position.y / size.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDragEnd = {
                                    val start = dragStart
                                    val end = dragCurrent
                                    if (start != null && end != null) {
                                        val area = RedactionArea(
                                            min(start.x, end.x), min(start.y, end.y),
                                            max(start.x, end.x), max(start.y, end.y)
                                        )
                                        if (area.right - area.left > 0.008f && area.bottom - area.top > 0.004f) {
                                            redactions = redactions.mapIndexed { index, list ->
                                                if (index == selectedPage) list + area else list
                                            }
                                            status = null
                                        }
                                    }
                                    dragStart = null
                                    dragCurrent = null
                                }
                            )
                        }
                    ) {
                        val current = redactions.getOrNull(selectedPage).orEmpty()
                        current.forEach { area ->
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(area.left * size.width, area.top * size.height),
                                size = Size((area.right - area.left) * size.width, (area.bottom - area.top) * size.height)
                            )
                            drawRect(
                                color = RedactAccent,
                                topLeft = Offset(area.left * size.width, area.top * size.height),
                                size = Size((area.right - area.left) * size.width, (area.bottom - area.top) * size.height),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                        val start = dragStart
                        val end = dragCurrent
                        if (start != null && end != null) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.72f),
                                topLeft = Offset(min(start.x, end.x) * size.width, min(start.y, end.y) * size.height),
                                size = Size(abs(end.x - start.x) * size.width, abs(end.y - start.y) * size.height)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(
                        onClick = { changePage(selectedPage - 1) },
                        enabled = selectedPage > 0 && !isWorking
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = { changePage(selectedPage + 1) },
                        enabled = selectedPage < pageCount - 1 && !isWorking
                    ) { Text("Next") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            redactions = redactions.mapIndexed { index, list ->
                                if (index == selectedPage) list.dropLast(1) else list
                            }
                            status = "Last redaction area removed from this page."
                        },
                        enabled = redactions.getOrNull(selectedPage).orEmpty().isNotEmpty() && !isWorking,
                        modifier = Modifier.weight(1f)
                    ) { Text("Undo last") }
                    OutlinedButton(
                        onClick = {
                            redactions = redactions.mapIndexed { index, list ->
                                if (index == selectedPage) emptyList() else list
                            }
                            status = "Redactions cleared from this page."
                        },
                        enabled = redactions.getOrNull(selectedPage).orEmpty().isNotEmpty() && !isWorking,
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear page") }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("Select a PDF to preview its pages.", color = RedactMuted, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF392630)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Important: permanent flattening", color = Color(0xFFFFB4AB),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "The saved PDF is rebuilt from page images with marked areas painted black. This removes the original text and objects from every page, not just the selected areas. Text search, selection, links, forms and vector quality will be lost. The original file is never changed.",
                        color = Color(0xFFE5D8C4), fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status!!, color = RedactMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showConfirmation = true },
                enabled = sourceUri != null && totalAreas > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RedactAccent,
                    disabledContainerColor = Color(0xFF51414A)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Create Redacted PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Create permanently redacted PDF?") },
            text = {
                Text("This creates a flattened copy. All original selectable text and PDF objects will be removed from the output, while the marked areas are covered in black. This cannot be undone in the new file. The source PDF remains unchanged.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val base = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                    savePicker.launch("${base}_redacted.pdf")
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

private fun renderRedactionPage(context: Context, uri: Uri, index: Int): Pair<Bitmap, Int> {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Could not open the PDF.")
    descriptor.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            require(index in 0 until renderer.pageCount) { "Page number is out of range." }
            renderer.openPage(index).use { page ->
                val scale = min(1.6f, min(1800f / page.width, 2400f / page.height)).coerceAtLeast(1f)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap to renderer.pageCount
            }
        }
    }
}

private fun getRedactionPdfName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

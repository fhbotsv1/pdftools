package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

private val AnnotateBackground = Color(0xFF101522)
private val AnnotateSurface = Color(0xFF1B2433)
private val AnnotateAccent = Color(0xFFEF6262)
private val AnnotateMuted = Color(0xFF9AA9BD)

private data class AnnotatePreview(val bitmap: Bitmap, val pageWidth: Float, val pageHeight: Float, val pageCount: Int)

@Composable
fun AnnotatePdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<AnnotatePreview?>(null) }
    var addWatermark by remember { mutableStateOf(true) }
    var watermarkText by remember { mutableStateOf("CONFIDENTIAL") }
    var watermarkSize by remember { mutableFloatStateOf(42f) }
    var watermarkX by remember { mutableFloatStateOf(0.22f) }
    var watermarkY by remember { mutableFloatStateOf(0.43f) }
    var addPageNumbers by remember { mutableStateOf(false) }
    var pagePrefix by remember { mutableStateOf("Page ") }
    var startingNumber by remember { mutableStateOf("1") }
    var pageNumberSize by remember { mutableFloatStateOf(10f) }
    var pageNumberX by remember { mutableFloatStateOf(0.42f) }
    var pageNumberY by remember { mutableFloatStateOf(0.93f) }
    var includeTotal by remember { mutableStateOf(true) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { previewFile?.delete() } }

    fun loadPreview(file: File, pageIndex: Int) {
        scope.launch {
            isWorking = true
            status = "Rendering page " + (pageIndex + 1) + "…"
            try {
                preview = withContext(Dispatchers.IO) { renderAnnotatePreview(file, pageIndex) }
                status = null
            } catch (error: Exception) {
                preview = null
                status = "Could not preview page: " + (error.localizedMessage ?: "Unsupported PDF")
            } finally { isWorking = false }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val inputUri = sourceUri
        if (destination != null && inputUri != null) {
            val watermarkEnabled = addWatermark
            val chosenWatermark = watermarkText.trim()
            val chosenWatermarkSize = watermarkSize
            val chosenWatermarkX = watermarkX
            val chosenWatermarkY = watermarkY
            val numbersEnabled = addPageNumbers
            val chosenPrefix = pagePrefix
            val firstNumber = startingNumber.toIntOrNull()
            val chosenNumberSize = pageNumberSize
            val chosenNumberX = pageNumberX
            val chosenNumberY = pageNumberY
            val showTotal = includeTotal
            if (watermarkEnabled && chosenWatermark.isBlank()) {
                status = "Enter watermark text, or turn off the watermark option."
                return@rememberLauncherForActivityResult
            }
            if (numbersEnabled && firstNumber == null) {
                status = "Enter a valid starting page number."
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                isWorking = true
                status = "Applying annotations to all pages…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(inputUri) ?: error("Could not open the selected PDF.")
                        val bytes = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                val total = document.numberOfPages
                                if (total == 0) error("The selected PDF has no pages.")
                                document.pages.forEachIndexed { index, page ->
                                    if (watermarkEnabled) addWatermarkToPage(document, page, chosenWatermark, chosenWatermarkSize, chosenWatermarkX, chosenWatermarkY)
                                    if (numbersEnabled) {
                                        val number = firstNumber!! + index
                                        val label = if (showTotal) chosenPrefix + number + " of " + total else chosenPrefix + number
                                        addPageNumberToPage(document, page, label, chosenNumberSize, chosenNumberX, chosenNumberY)
                                    }
                                }
                                ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
                            }
                        }
                        val output = context.contentResolver.openOutputStream(destination) ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Annotated PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not annotate PDF: " + (error.localizedMessage ?: "Check that the PDF is valid and not password-protected.")
                } finally { isWorking = false }
            }
        } else if (destination == null) status = "Saving cancelled."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Loading PDF and preparing preview…"
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) { }
                    val prepared = withContext(Dispatchers.IO) {
                        val file = File(context.cacheDir, "annotate_preview_" + System.currentTimeMillis() + ".pdf")
                        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected PDF.")
                        input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
                        Pair(file, renderAnnotatePreview(file, 0))
                    }
                    previewFile?.delete()
                    previewFile = prepared.first
                    sourceUri = uri
                    sourceName = getAnnotatePdfDisplayName(context, uri)
                    pageCount = prepared.second.pageCount
                    selectedPage = 0
                    preview = prepared.second
                    watermarkX = 0.22f; watermarkY = 0.43f
                    pageNumberX = 0.42f; pageNumberY = 0.93f
                    status = "Drag either text sample on the page to position it."
                } catch (error: Exception) {
                    sourceUri = null; sourceName = null; preview = null
                    status = "Could not read PDF: " + (error.localizedMessage ?: "Check that the file is valid and unlocked.")
                } finally { isWorking = false }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AnnotateAccent, surface = AnnotateSurface)) {
        Column(modifier.fillMaxSize().background(AnnotateBackground).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("Annotate PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Preview, position and size your text", color = AnnotateMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AnnotateSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("PDF page preview", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { picker.launch(arrayOf("application/pdf")) }, enabled = !isWorking, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AnnotateAccent), shape = RoundedCornerShape(12.dp)) {
                            Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        if (sourceName != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(sourceName!!, color = AnnotateMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (preview != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                OutlinedButton(onClick = { if (selectedPage > 0) { selectedPage--; previewFile?.let { loadPreview(it, selectedPage) } } }, enabled = selectedPage > 0 && !isWorking) { Text("Previous") }
                                Text("Page " + (selectedPage + 1) + " of " + pageCount, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                OutlinedButton(onClick = { if (selectedPage < pageCount - 1) { selectedPage++; previewFile?.let { loadPreview(it, selectedPage) } } }, enabled = selectedPage < pageCount - 1 && !isWorking) { Text("Next") }
                            }
                            Spacer(Modifier.height(8.dp))
                            AnnotatePagePreview(
                                preview!!, addWatermark, watermarkText, watermarkSize, watermarkX, watermarkY,
                                { x, y -> watermarkX = x; watermarkY = y },
                                addPageNumbers,
                                pagePrefix + ((startingNumber.toIntOrNull() ?: 1) + selectedPage) + if (includeTotal) " of " + pageCount else "",
                                pageNumberSize, pageNumberX, pageNumberY,
                                { x, y -> pageNumberX = x; pageNumberY = y }
                            )
                            Text("Drag the watermark or page number on the preview to move it.", color = AnnotateMuted, fontSize = 11.sp)
                        } else {
                            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF263246)), contentAlignment = Alignment.Center) {
                                Text("Select a PDF to see a live page preview", color = AnnotateMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AnnotateSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Watermark", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("Drag its sample on the preview", color = AnnotateMuted, fontSize = 11.sp)
                            }
                            Switch(addWatermark, { addWatermark = it }, enabled = !isWorking)
                        }
                        if (addWatermark) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(watermarkText, { watermarkText = it }, Modifier.fillMaxWidth(), label = { Text("Watermark text") }, singleLine = true, enabled = !isWorking, colors = annotateTextFieldColors())
                            Spacer(Modifier.height(6.dp))
                            Text("Text size: " + watermarkSize.toInt() + " pt", color = AnnotateMuted, fontSize = 12.sp)
                            Slider(watermarkSize, { watermarkSize = it }, valueRange = 12f..96f, enabled = !isWorking, colors = SliderDefaults.colors(thumbColor = AnnotateAccent, activeTrackColor = AnnotateAccent))
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AnnotateSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Page numbers", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("Drag its sample on the preview", color = AnnotateMuted, fontSize = 11.sp)
                            }
                            Switch(addPageNumbers, { addPageNumbers = it }, enabled = !isWorking)
                        }
                        if (addPageNumbers) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(pagePrefix, { pagePrefix = it }, Modifier.fillMaxWidth(), label = { Text("Text before number") }, singleLine = true, enabled = !isWorking, colors = annotateTextFieldColors())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(startingNumber, { startingNumber = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Starting number") }, singleLine = true, enabled = !isWorking, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = annotateTextFieldColors())
                            Spacer(Modifier.height(4.dp))
                            Text("Text size: " + pageNumberSize.toInt() + " pt", color = AnnotateMuted, fontSize = 12.sp)
                            Slider(pageNumberSize, { pageNumberSize = it }, valueRange = 6f..36f, enabled = !isWorking, colors = SliderDefaults.colors(thumbColor = AnnotateAccent, activeTrackColor = AnnotateAccent))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(includeTotal, { includeTotal = it }, enabled = !isWorking, colors = CheckboxDefaults.colors(checkedColor = AnnotateAccent))
                                Text("Include total page count (e.g. Page 1 of 10)", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF302A32)), shape = RoundedCornerShape(14.dp)) {
                    Text("The chosen layout is applied to every page. Preview any page before saving. The original PDF is kept unchanged. Use basic English/Latin characters for annotation text.",
                        color = Color(0xFFFFD7D2), fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(12.dp))
                }
            }
            if (status != null) {
                Spacer(Modifier.height(6.dp))
                Text(status!!, color = AnnotateMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val name = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                saveLauncher.launch(name + "_annotated.pdf")
            }, enabled = sourceUri != null && (addWatermark || addPageNumbers) && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AnnotateAccent, disabledContainerColor = Color(0xFF51414A)),
                shape = RoundedCornerShape(14.dp)) {
                if (isWorking) {
                    CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else Text("Save Annotated PDF", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AnnotatePagePreview(
    preview: AnnotatePreview,
    watermarkEnabled: Boolean, watermarkText: String, watermarkSize: Float, watermarkX: Float, watermarkY: Float,
    onWatermarkPosition: (Float, Float) -> Unit,
    pageNumberEnabled: Boolean, pageNumberText: String, pageNumberSize: Float, pageNumberX: Float, pageNumberY: Float,
    onPageNumberPosition: (Float, Float) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val previewWidth = maxWidth
        val pageAspect = preview.pageWidth / preview.pageHeight
        val previewHeight = previewWidth / pageAspect
        val scale = previewWidth.value / preview.pageWidth
        val density = androidx.compose.ui.platform.LocalDensity.current
        val previewWidthPx = with(density) { previewWidth.toPx() }
        val previewHeightPx = with(density) { previewHeight.toPx() }
        val latestWatermarkX by rememberUpdatedState(watermarkX)
        val latestWatermarkY by rememberUpdatedState(watermarkY)
        val latestPageNumberX by rememberUpdatedState(pageNumberX)
        val latestPageNumberY by rememberUpdatedState(pageNumberY)
        Box(Modifier.fillMaxWidth().height(previewHeight)) {
            Image(preview.bitmap.asImageBitmap(), "Selected PDF page preview", Modifier.fillMaxSize())
            if (watermarkEnabled && watermarkText.isNotBlank()) {
                Text(watermarkText, color = Color(0xFF9A9A9A), fontSize = (watermarkSize * scale).coerceIn(7f, 42f).sp,
                    fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.offset(x = previewWidth * watermarkX, y = previewHeight * watermarkY).graphicsLayer(rotationZ = 45f)
                        .pointerInput(preview.pageWidth, preview.pageHeight) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onWatermarkPosition((latestWatermarkX + drag.x / previewWidthPx).coerceIn(0f, 0.88f), (latestWatermarkY + drag.y / previewHeightPx).coerceIn(0f, 0.94f))
                            }
                        })
            }
            if (pageNumberEnabled && pageNumberText.isNotBlank()) {
                Text(pageNumberText, color = Color(0xFF444444), fontSize = (pageNumberSize * scale).coerceIn(6f, 24f).sp, maxLines = 1,
                    modifier = Modifier.offset(x = previewWidth * pageNumberX, y = previewHeight * pageNumberY)
                        .pointerInput(preview.pageWidth, preview.pageHeight) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                onPageNumberPosition((latestPageNumberX + drag.x / previewWidthPx).coerceIn(0f, 0.9f), (latestPageNumberY + drag.y / previewHeightPx).coerceIn(0f, 0.97f))
                            }
                        })
            }
        }
    }
}

@Composable
private fun annotateTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = AnnotateAccent, unfocusedBorderColor = AnnotateMuted,
    focusedLabelColor = AnnotateAccent, unfocusedLabelColor = AnnotateMuted, cursorColor = AnnotateAccent
)

private fun renderAnnotatePreview(file: File, pageIndex: Int): AnnotatePreview {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    PdfRenderer(descriptor).use { renderer ->
        if (pageIndex !in 0 until renderer.pageCount) error("Page number is out of range.")
        renderer.openPage(pageIndex).use { page ->
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return AnnotatePreview(bitmap, page.width.toFloat(), page.height.toFloat(), renderer.pageCount)
        }
    }
}

private fun addWatermarkToPage(document: PDDocument, page: PDPage, text: String, fontSize: Float, xFraction: Float, yFraction: Float) {
    val font = PDType1Font.HELVETICA_BOLD
    val safeText = text.replace(Regex("[^\\x20-\\x7E]"), "?")
    val box = page.mediaBox
    val x = (box.width * xFraction).coerceIn(0f, box.width)
    val y = (box.height * (1f - yFraction)).coerceIn(0f, box.height)
    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
        stream.setNonStrokingColor(190, 190, 190)
        stream.beginText(); stream.setFont(font, fontSize)
        stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), x, y))
        stream.showText(safeText); stream.endText()
    }
}

private fun addPageNumberToPage(document: PDDocument, page: PDPage, text: String, fontSize: Float, xFraction: Float, yFraction: Float) {
    val font = PDType1Font.HELVETICA
    val safeText = text.replace(Regex("[^\\x20-\\x7E]"), "?")
    val box = page.mediaBox
    val x = (box.width * xFraction).coerceIn(0f, box.width)
    val y = (box.height * (1f - yFraction)).coerceIn(0f, box.height)
    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
        stream.setNonStrokingColor(70, 70, 70)
        stream.beginText(); stream.setFont(font, fontSize); stream.newLineAtOffset(x, y)
        stream.showText(safeText); stream.endText()
    }
}

private fun getAnnotatePdfDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

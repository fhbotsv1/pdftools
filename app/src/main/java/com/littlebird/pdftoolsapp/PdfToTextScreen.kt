package com.littlebird.pdftoolsapp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PdfTextBackground = Color(0xFF101522)
private val PdfTextSurface = Color(0xFF1B2433)
private val PdfTextAccent = Color(0xFFEF6262)
private val PdfTextMuted = Color(0xFF9AA9BD)

@Composable
fun PdfToTextScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var thumbnails by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var allPagesSelected by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingExportText by remember { mutableStateOf<String?>(null) }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destinationUri ->
        if (destinationUri == null) {
            status = "Save cancelled."
        } else {
            val pendingText = pendingExportText
            if (pendingText == null) {
                status = "There is no extracted text to save."
            } else {
                scope.launch {
                    isWorking = true
                    try {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(destinationUri)
                                ?: error("Could not open the selected save location.")
                            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                                writer.write(pendingText)
                            }
                        }
                        status = "Text file saved successfully."
                    } catch (error: Exception) {
                        status = "Could not save text: ${error.localizedMessage ?: "Unknown error"}"
                    } finally {
                        isWorking = false
                        pendingExportText = null
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getPdfTextDisplayName(context, uri)
            pageCount = 0
            thumbnails = emptyList()
            selectedPages = emptySet()
            allPagesSelected = false
            status = "Loading PDF pages…"
            scope.launch {
                isWorking = true
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                val renderer = PDFRenderer(document)
                                val previews = ArrayList<Bitmap?>(document.numberOfPages)
                                for (index in 0 until document.numberOfPages) {
                                    val preview = try {
                                        renderer.renderImage(index, 0.25f)
                                    } catch (_: Exception) {
                                        null
                                    }
                                    previews.add(preview)
                                }
                                Pair(document.numberOfPages, previews)
                            }
                        }
                    }
                    pageCount = loaded.first
                    thumbnails = loaded.second
                    status = "PDF loaded. Select the pages whose text you want to extract."
                } catch (error: Exception) {
                    status = "Could not read PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                }
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary access is sufficient for this session.
            }
        }
    }

    fun startExtraction() {
        val uri = sourceUri ?: return
        if (isWorking) return
        val pagesToProcess = if (allPagesSelected) {
            (0 until pageCount).toList()
        } else {
            selectedPages.sorted()
        }
        if (pagesToProcess.isEmpty()) {
            status = "Select at least one page, or choose all pages."
            return
        }

        scope.launch {
            isWorking = true
            status = "Extracting text from ${pagesToProcess.size} page(s)…"
            try {
                val extracted = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            buildString {
                                pagesToProcess.forEachIndexed { selectedIndex, pageIndex ->
                                    if (pageIndex !in 0 until document.numberOfPages) return@forEachIndexed
                                    val stripper = PDFTextStripper().apply {
                                        setSortByPosition(true)
                                        setStartPage(pageIndex + 1)
                                        setEndPage(pageIndex + 1)
                                        setWordSeparator(" ")
                                        setLineSeparator("\n")
                                        setParagraphStart("\n\n")
                                        setParagraphEnd("\n\n")
                                        setPageStart("")
                                        setPageEnd("")
                                    }
                                    val pageText = stripper.getText(document).trimEnd()
                                    if (selectedIndex > 0) append("\n\n")
                                    append(pageText)
                                }
                            }
                        }
                    }
                }

                if (extracted.isBlank()) {
                    status = "No selectable text was found on the selected pages. If these are scanned pages, OCR is required."
                } else {
                    val baseName = (sourceName ?: "document").substringBeforeLast(
                        '.', sourceName ?: "document"
                    )
                    pendingExportText = extracted
                    status = "Text extracted. Choose where to save the .txt file."
                    savePicker.launch("${baseName}_text.txt")
                }
            } catch (error: Exception) {
                status = "Could not extract text: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                isWorking = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = PdfTextAccent, surface = PdfTextSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(PdfTextBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("PDF to Text", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Extract text from all or selected pages", color = PdfTextMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = PdfTextSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose PDF file", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PdfTextAccent)
                    ) {
                        Text(if (sourceName == null) "Select PDF" else "Choose another PDF", color = Color.White)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(sourceName!!, color = PdfTextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount page(s)", color = PdfTextAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B3021))
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Important: selectable-text PDFs only", color = Color(0xFFFFD18A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This tool extracts text from digitally created PDFs, such as Word to PDF files. It will not read text that exists only inside scanned pages or photos. For scanned/image PDFs, use the separate OCR PDF tool under Edit.",
                        color = Color(0xFFE5D8C4), fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (sourceUri != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(PdfTextSurface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allPagesSelected,
                        onCheckedChange = {
                            allPagesSelected = it
                            selectedPages = if (it) (0 until pageCount).toSet() else emptySet()
                        },
                        enabled = !isWorking
                    )
                    Text("Extract text from all pages", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    if (allPagesSelected) "All $pageCount pages selected"
                    else "${selectedPages.size} of $pageCount page(s) selected",
                    color = PdfTextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            }

            if (sourceUri != null) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    itemsIndexed(thumbnails) { index, bitmap ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(PdfTextSurface, RoundedCornerShape(12.dp))
                                .clickable(enabled = !isWorking) {
                                    selectedPages = if (index in selectedPages) selectedPages - index else selectedPages + index
                                    allPagesSelected = false
                                }
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "PDF page ${index + 1}",
                                    modifier = Modifier.width(54.dp).height(68.dp)
                                )
                            } else {
                                Box(
                                    Modifier.width(54.dp).height(68.dp).background(Color(0xFF344052)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${index + 1}", color = Color.White)
                                }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Page ${index + 1}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    if (index in selectedPages || allPagesSelected) "Selected for text extraction" else "Tap to select this page",
                                    color = PdfTextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Checkbox(
                                checked = index in selectedPages || allPagesSelected,
                                onCheckedChange = { checked ->
                                    selectedPages = if (checked) selectedPages + index else selectedPages - index
                                    allPagesSelected = false
                                },
                                enabled = !isWorking
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (status != null) {
                Text(status!!, color = PdfTextMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 7.dp))
            }
            Button(
                onClick = { startExtraction() },
                enabled = sourceUri != null && pageCount > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PdfTextAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Processing…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Extract Text and Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


private fun getPdfTextDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

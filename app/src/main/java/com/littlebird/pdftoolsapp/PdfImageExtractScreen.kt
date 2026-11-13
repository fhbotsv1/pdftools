package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap

private val PdfImageBackground = Color(0xFF101522)
private val PdfImageSurface = Color(0xFF1B2433)
private val PdfImageAccent = Color(0xFFEF6262)
private val PdfImageMuted = Color(0xFF9AA9BD)

private data class ExportImage(val fileName: String, val bytes: ByteArray)

@Composable
fun PdfImageExtractScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var thumbnails by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var convertPdfToImage by remember { mutableStateOf(false) }
    var allPagesSelected by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var exportQueue by remember { mutableStateOf<List<ExportImage>>(emptyList()) }
    var exportIndex by remember { mutableIntStateOf(0) }
    var queueActive by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { destination ->
        if (destination == null) {
            queueActive = false
            status = "Saving cancelled. Previously saved images are unchanged."
        } else {
            val item = exportQueue.getOrNull(exportIndex)
            if (item == null) {
                queueActive = false
            } else {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(destination)?.use {
                                it.write(item.bytes)
                            } ?: error("Could not open the selected save location.")
                        }
                        exportIndex++
                        if (exportIndex >= exportQueue.size) {
                            queueActive = false
                            status = "Saved all ${exportQueue.size} image(s)."
                        } else {
                            status = "Saved ${exportIndex} of ${exportQueue.size} image(s). Choose the next save location."
                        }
                    } catch (error: Exception) {
                        queueActive = false
                        status = "Could not save image: ${error.localizedMessage ?: "Unknown error"}"
                    }
                }
            }
        }
    }

    LaunchedEffect(queueActive, exportIndex, exportQueue) {
        if (queueActive && exportIndex < exportQueue.size) {
            saveLauncher.launch(exportQueue[exportIndex].fileName)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getPdfImageDisplayName(context, uri)
            selectedPages = emptySet()
            allPagesSelected = false
            pageCount = 0
            thumbnails = emptyList()
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
                    selectedPages = (0 until loaded.first).toSet()
                    status = "PDF loaded. Select pages to extract embedded images, or switch to PDF to Image."
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

    fun beginExport(items: List<ExportImage>) {
        if (items.isEmpty()) {
            status = "No images were found for the selected operation."
            return
        }
        exportQueue = items
        exportIndex = 0
        queueActive = true
        status = "Preparing to save ${items.size} image(s)."
    }

    fun startExport() {
        val uri = sourceUri ?: return
        if (isWorking || queueActive) return
        val pagesToProcess = if (allPagesSelected) {
            (0 until pageCount).toList()
        } else {
            selectedPages.sorted()
        }
        if (!convertPdfToImage && pagesToProcess.isEmpty()) {
            status = "Select at least one page, or choose all pages."
            return
        }
        scope.launch {
            isWorking = true
            status = if (convertPdfToImage) "Converting every PDF page to an image…" else "Extracting embedded images…"
            try {
                val outputs = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            val base = (sourceName ?: "document").substringBeforeLast('.', sourceName ?: "document")
                            val result = mutableListOf<ExportImage>()
                            if (convertPdfToImage) {
                                val renderer = PDFRenderer(document)
                                for (pageIndex in 0 until document.numberOfPages) {
                                    val bitmap = renderer.renderImage(pageIndex, 2f)
                                    try {
                                        val bytes = ByteArrayOutputStream().use { output ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                                            output.toByteArray()
                                        }
                                        result.add(ExportImage("${base}_page_${pageIndex + 1}.png", bytes))
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            } else {
                                val visitedForms = Collections.newSetFromMap(
                                    IdentityHashMap<PDFormXObject, Boolean>()
                                )
                                for (pageIndex in pagesToProcess) {
                                    if (pageIndex !in 0 until document.numberOfPages) continue
                                    val pageResources = document.getPage(pageIndex).resources
                                    val images = extractImagesFromResources(
                                        pageResources, visitedForms
                                    )
                                    images.forEachIndexed { imageIndex, bytes ->
                                        result.add(
                                            ExportImage(
                                                "${base}_page_${pageIndex + 1}_image_${imageIndex + 1}.png",
                                                bytes
                                            )
                                        )
                                    }
                                }
                            }
                            result
                        }
                    }
                }
                beginExport(outputs)
            } catch (error: Exception) {
                status = "Could not process PDF: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                isWorking = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = PdfImageAccent, surface = PdfImageSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(PdfImageBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("PDF to Image / Extract", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Convert pages or extract embedded images", color = PdfImageMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = PdfImageSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose PDF file", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking && !queueActive,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PdfImageAccent)
                    ) {
                        Text(if (sourceName == null) "Select PDF" else "Choose another PDF", color = Color.White)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(sourceName!!, color = PdfImageMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount page(s)", color = PdfImageAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = PdfImageSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !convertPdfToImage,
                            onClick = { convertPdfToImage = false },
                            enabled = !isWorking && !queueActive
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Extract Images", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Save embedded images found inside selected pages", color = PdfImageMuted, fontSize = 10.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = convertPdfToImage,
                            onClick = { convertPdfToImage = true },
                            enabled = !isWorking && !queueActive
                        )
                        Column(Modifier.weight(1f)) {
                            Text("PDF to Image", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Convert every PDF page into a separate PNG image", color = PdfImageMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (sourceUri != null && !convertPdfToImage) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().background(PdfImageSurface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allPagesSelected,
                        onCheckedChange = {
                            allPagesSelected = it
                            selectedPages = if (it) (0 until pageCount).toSet() else emptySet()
                        },
                        enabled = !isWorking && !queueActive
                    )
                    Text("Extract images from all pages", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    if (allPagesSelected) "All $pageCount pages selected" else "${selectedPages.size} of $pageCount page(s) selected",
                    color = PdfImageMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 5.dp)
                )
            }

            if (sourceUri != null && convertPdfToImage) {
                Spacer(Modifier.height(8.dp))
                Text("All $pageCount pages will be converted and saved as separate PNG images.", color = PdfImageMuted, fontSize = 12.sp)
            }

            if (sourceUri != null && !convertPdfToImage) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    itemsIndexed(thumbnails) { index, bitmap ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(PdfImageSurface, RoundedCornerShape(12.dp))
                                .clickable(enabled = !allPagesSelected && !isWorking && !queueActive) {
                                    selectedPages = if (index in selectedPages) selectedPages - index else selectedPages + index
                                }
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "PDF page ${index + 1}",
                                    modifier = Modifier.width(54.dp).height(68.dp)
                                )
                            } else {
                                Box(Modifier.width(54.dp).height(68.dp).background(Color(0xFF344052)), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", color = Color.White)
                                }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Page ${index + 1}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(if (index in selectedPages) "Selected for image extraction" else "Tap to select this page", color = PdfImageMuted, fontSize = 10.sp)
                            }
                            Checkbox(
                                checked = index in selectedPages,
                                onCheckedChange = { checked ->
                                    selectedPages = if (checked) selectedPages + index else selectedPages - index
                                },
                                enabled = !allPagesSelected && !isWorking && !queueActive
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (status != null) {
                Text(status!!, color = PdfImageMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 7.dp))
            }
            Button(
                onClick = { startExport() },
                enabled = sourceUri != null && pageCount > 0 && !isWorking && !queueActive,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PdfImageAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Processing…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text(if (convertPdfToImage) "Convert PDF to Images" else "Extract Images and Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun extractImagesFromResources(
    resources: PDResources?,
    visitedForms: MutableSet<PDFormXObject>
): List<ByteArray> {
    if (resources == null) return emptyList()
    val result = mutableListOf<ByteArray>()
    for (name in resources.xObjectNames.toList()) {
        val xObject: PDXObject = try {
            resources.getXObject(name) ?: continue
        } catch (_: Exception) {
            continue
        }
        when (xObject) {
            is PDImageXObject -> {
                try {
                    val bitmap = xObject.image ?: continue
                    try {
                        val bytes = ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            output.toByteArray()
                        }
                        if (bytes.isNotEmpty()) result.add(bytes)
                    } finally {
                        bitmap.recycle()
                    }
                } catch (_: Exception) {
                    // Skip images that PDFBox cannot decode.
                }
            }
            is PDFormXObject -> {
                if (visitedForms.add(xObject)) {
                    result.addAll(extractImagesFromResources(xObject.resources, visitedForms))
                }
            }
        }
    }
    return result
}

private fun getPdfImageDisplayName(context: Context, uri: Uri): String {
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

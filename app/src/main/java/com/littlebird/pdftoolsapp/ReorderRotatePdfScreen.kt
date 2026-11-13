package com.littlebird.pdftoolsapp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val ReorderBackground = Color(0xFF101522)
private val ReorderSurface = Color(0xFF1B2433)
private val ReorderAccent = Color(0xFFEF6262)
private val ReorderMuted = Color(0xFF9AA9BD)

private data class ReorderPage(
    val sourceIndex: Int,
    val rotation: Int,
    val width: Float,
    val height: Float
) {
    val orientation: String
        get() {
            val isLandscape = if (rotation % 180 == 0) width > height else height > width
            return if (isLandscape) "Landscape" else "Portrait"
        }
}

@Composable
fun ReorderRotatePdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableStateOf<List<ReorderPage>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val uri = sourceUri
        val pagePlan = pages.toList()
        if (destination != null && uri != null && pagePlan.isNotEmpty()) {
            scope.launch {
                isWorking = true
                status = "Saving updated PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        val outputBytes = input.use { stream ->
                            PDDocument.load(stream).use { source ->
                                PDDocument().use { result ->
                                    pagePlan.forEach { item ->
                                        val sourcePage = source.getPage(item.sourceIndex)
                                        val newPage = result.importPage(sourcePage)
                                        newPage.rotation = item.rotation
                                    }
                                    ByteArrayOutputStream().use { buffer ->
                                        result.save(buffer)
                                        buffer.toByteArray()
                                    }
                                }
                            }
                        }
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(outputBytes) }
                    }
                    status = "PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not save PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                }
            }
        } else if (destination == null) {
            status = "Saving cancelled."
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Reading PDF pages…"
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        // Temporary access is sufficient for this session.
                    }
                    val loadedPages = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                (0 until document.numberOfPages).map { index ->
                                    val page = document.getPage(index)
                                    ReorderPage(
                                        sourceIndex = index,
                                        rotation = ((page.rotation % 360) + 360) % 360,
                                        width = page.mediaBox.width,
                                        height = page.mediaBox.height
                                    )
                                }
                            }
                        }
                    }
                    sourceUri = uri
                    sourceName = getReorderPdfDisplayName(context, uri)
                    pages = loadedPages
                    status = "Loaded ${loadedPages.size} pages. Reorder or change orientation, then save."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pages = emptyList()
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    fun movePage(index: Int, offset: Int) {
        val target = index + offset
        if (target !in pages.indices) return
        val updated = pages.toMutableList()
        val item = updated[index]
        updated[index] = updated[target]
        updated[target] = item
        pages = updated
        status = null
    }

    fun toggleOrientation(index: Int) {
        pages = pages.mapIndexed { i, page ->
            if (i == index) page.copy(rotation = (page.rotation + 90) % 360) else page
        }
        status = null
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = ReorderAccent, surface = ReorderSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(ReorderBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Reorder / Rotate", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Move pages and switch their orientation", color = ReorderMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReorderSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ReorderAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${pages.size} pages", color = ReorderAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PDF pages", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use arrows to change order. Tap orientation to switch.", color = ReorderMuted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (pages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Select a PDF to display its page list.", color = ReorderMuted, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pages.forEachIndexed { index, page ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(13.dp),
                            colors = CardDefaults.cardColors(containerColor = ReorderSurface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(38.dp).background(Color(0xFF3A2933), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${index + 1}", color = ReorderAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Page ${index + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Original page ${page.sourceIndex + 1}", color = ReorderMuted, fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Orientation", color = ReorderMuted, fontSize = 9.sp)
                                    Surface(
                                        color = Color(0xFF3A2933),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.clickable(enabled = !isWorking) { toggleOrientation(index) }
                                    ) {
                                        Text(
                                            page.orientation,
                                            color = ReorderAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TextButton(
                                        onClick = { movePage(index, -1) },
                                        enabled = index > 0 && !isWorking,
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) { Text("↑", fontSize = 19.sp, color = if (index > 0) Color.White else ReorderMuted) }
                                    TextButton(
                                        onClick = { movePage(index, 1) },
                                        enabled = index < pages.lastIndex && !isWorking,
                                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) { Text("↓", fontSize = 19.sp, color = if (index < pages.lastIndex) Color.White else ReorderMuted) }
                                }
                            }
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status!!, color = ReorderMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val name = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                    saveLauncher.launch("${name}_reordered.pdf")
                },
                enabled = sourceUri != null && pages.isNotEmpty() && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ReorderAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Save Updated PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getReorderPdfDisplayName(context: Context, uri: Uri): String {
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

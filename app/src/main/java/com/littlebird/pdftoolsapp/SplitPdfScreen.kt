package com.littlebird.pdftoolsapp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val SplitPdfBackground = Color(0xFF101522)
private val SplitPdfSurface = Color(0xFF1B2433)
private val SplitPdfAccent = Color(0xFFEF6262)
private val SplitPdfMuted = Color(0xFF9AA9BD)

private data class SplitPdfOutput(val fileName: String, val bytes: ByteArray)

@Composable
fun SplitPdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var startPage by remember { mutableStateOf("1") }
    var endPage by remember { mutableStateOf("") }
    var pagesPerFile by remember { mutableStateOf("1") }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var outputQueue by remember { mutableStateOf<List<SplitPdfOutput>>(emptyList()) }
    var totalOutputs by remember { mutableIntStateOf(0) }
    var savedOutputs by remember { mutableIntStateOf(0) }
    var saveDialogOpen by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        if (destination == null) {
            outputQueue = emptyList()
            saveDialogOpen = false
            status = "Saving cancelled. $savedOutputs of $totalOutputs files saved."
        } else {
            val output = outputQueue.firstOrNull()
            if (output == null) {
                saveDialogOpen = false
            } else {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openOutputStream(destination)
                                ?: error("Could not open the selected save location.")
                            stream.use { it.write(output.bytes) }
                        }
                        savedOutputs += 1
                        outputQueue = outputQueue.drop(1)
                        status = "Saved $savedOutputs of $totalOutputs files…"
                    } catch (error: Exception) {
                        outputQueue = emptyList()
                        status = "Could not save PDF: ${error.localizedMessage ?: "Unknown error"}"
                    } finally {
                        saveDialogOpen = false
                    }
                }
            }
        }
    }

    LaunchedEffect(outputQueue, saveDialogOpen) {
        if (outputQueue.isNotEmpty() && !saveDialogOpen) {
            saveDialogOpen = true
            saveLauncher.launch(outputQueue.first().fileName)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Reading PDF…"
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                        // The picker may grant temporary access only.
                    }
                    val details = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                document.numberOfPages
                            }
                        }
                    }
                    sourceUri = uri
                    sourceName = getSplitPdfDisplayName(context, uri)
                    pageCount = details
                    startPage = "1"
                    endPage = details.toString()
                    pagesPerFile = "1"
                    status = if (details > 0) "PDF loaded. It contains $details pages." else "This PDF has no pages."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pageCount = 0
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is a valid, unlocked PDF."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    fun splitPdf() {
        val uri = sourceUri ?: return
        val start = startPage.toIntOrNull()
        val end = endPage.toIntOrNull()
        val groupSize = pagesPerFile.toIntOrNull()
        if (start == null || end == null || groupSize == null ||
            start < 1 || end < start || end > pageCount || groupSize < 1
        ) {
            status = "Enter valid page numbers and a pages-per-file value."
            return
        }
        if (isWorking) return

        isWorking = true
        status = "Splitting PDF…"
        scope.launch {
            try {
                val outputs = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        val source = PDDocument.load(stream)
                        try {
                            val splitter = Splitter()
                            splitter.setStartPage(start)
                            splitter.setEndPage(end)
                            splitter.setSplitAtPage(groupSize)
                            val documents = splitter.split(source)
                            try {
                                documents.mapIndexed { index, document ->
                                    try {
                                        val bytes = ByteArrayOutputStream().use { output ->
                                            document.save(output)
                                            output.toByteArray()
                                        }
                                        val baseName = (sourceName ?: "Document")
                                            .substringBeforeLast('.', sourceName ?: "Document")
                                        SplitPdfOutput(
                                            fileName = "${baseName}_part_${index + 1}.pdf",
                                            bytes = bytes
                                        )
                                    } finally {
                                        document.close()
                                    }
                                }
                            } catch (error: Exception) {
                                documents.forEach { runCatching { it.close() } }
                                throw error
                            }
                        } finally {
                            source.close()
                        }
                    }
                }
                if (outputs.isEmpty()) {
                    status = "No pages were available in the selected range."
                } else {
                    outputQueue = outputs
                    totalOutputs = outputs.size
                    savedOutputs = 0
                    status = "Choose where to save the first of ${outputs.size} PDF files."
                }
            } catch (error: Exception) {
                status = "Could not split PDF: ${error.localizedMessage ?: "Check that the PDF is valid and unlocked."}"
            } finally {
                isWorking = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = SplitPdfAccent, surface = SplitPdfSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(SplitPdfBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Split PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Separate pages into multiple PDF files", color = SplitPdfMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SplitPdfSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Select a PDF to see its page count and choose how to divide it.",
                        color = SplitPdfMuted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SplitPdfAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages", color = SplitPdfAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SplitPdfSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Split settings", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Start page", color = SplitPdfMuted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = startPage,
                                onValueChange = { startPage = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = pageCount > 0 && !isWorking,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = SplitPdfAccent,
                                    unfocusedBorderColor = SplitPdfMuted,
                                    disabledTextColor = SplitPdfMuted
                                )
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text("End page", color = SplitPdfMuted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = endPage,
                                onValueChange = { endPage = it.filter(Char::isDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = pageCount > 0 && !isWorking,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = SplitPdfAccent,
                                    unfocusedBorderColor = SplitPdfMuted,
                                    disabledTextColor = SplitPdfMuted
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Pages per output PDF", color = SplitPdfMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = pagesPerFile,
                        onValueChange = { pagesPerFile = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = pageCount > 0 && !isWorking,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = SplitPdfAccent,
                            unfocusedBorderColor = SplitPdfMuted,
                            disabledTextColor = SplitPdfMuted
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Example: pages 1–12 with 3 pages per file creates four PDFs. Use 1 to save every page as a separate PDF.",
                        color = SplitPdfMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(status!!, color = SplitPdfMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = { splitPdf() },
                enabled = sourceUri != null && pageCount > 0 && !isWorking && outputQueue.isEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SplitPdfAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Split PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getSplitPdfDisplayName(context: Context, uri: Uri): String {
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

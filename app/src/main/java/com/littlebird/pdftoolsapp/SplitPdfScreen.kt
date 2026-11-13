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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

private data class SplitPdfRange(val id: Int, val start: String, val end: String)
private data class SplitPdfOutput(val fileName: String, val bytes: ByteArray)

@Composable
fun SplitPdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var ranges by remember { mutableStateOf(listOf(SplitPdfRange(1, "1", ""))) }
    var nextRangeId by remember { mutableIntStateOf(2) }
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
                        // Temporary access is sufficient for this session.
                    }
                    val count = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { it.numberOfPages }
                        }
                    }
                    sourceUri = uri
                    sourceName = getSplitPdfDisplayName(context, uri)
                    pageCount = count
                    ranges = listOf(SplitPdfRange(1, "1", count.toString()))
                    nextRangeId = 2
                    status = if (count > 0) "PDF loaded. It contains $count pages." else "This PDF has no pages."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pageCount = 0
                    ranges = listOf(SplitPdfRange(1, "1", ""))
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is a valid, unlocked PDF."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    fun updateRange(id: Int, start: String? = null, end: String? = null) {
        ranges = ranges.map { range ->
            if (range.id == id) range.copy(start = start ?: range.start, end = end ?: range.end)
            else range
        }
    }

    fun addRange() {
        val previousEnd = ranges.lastOrNull()?.end?.toIntOrNull()
        val suggestedStart = if (previousEnd != null) (previousEnd + 1).coerceAtMost(pageCount.coerceAtLeast(1)) else 1
        ranges = ranges + SplitPdfRange(nextRangeId, suggestedStart.toString(), pageCount.toString())
        nextRangeId += 1
        status = null
    }

    fun splitPdf() {
        val uri = sourceUri ?: return
        if (isWorking) return
        val parsedRanges = ranges.mapIndexed { index, range ->
            val start = range.start.toIntOrNull()
            val end = range.end.toIntOrNull()
            if (start == null || end == null || start < 1 || end < start || end > pageCount) {
                status = "Split ${index + 1}: enter a valid start and end page between 1 and $pageCount."
                return
            }
            start to end
        }
        if (parsedRanges.isEmpty()) {
            status = "Add at least one split range."
            return
        }
        val sorted = parsedRanges.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            if (sorted[i].first <= sorted[i - 1].second) {
                status = "Split ranges overlap. Please adjust the start and end pages."
                return
            }
        }

        isWorking = true
        status = "Creating ${parsedRanges.size} split PDF file(s)…"
        val rangesToProcess = parsedRanges.toList()
        val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
        scope.launch {
            try {
                val outputs = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        val source = PDDocument.load(stream)
                        try {
                            rangesToProcess.mapIndexed { index, (start, end) ->
                                val splitter = Splitter()
                                splitter.setStartPage(start)
                                splitter.setEndPage(end)
                                splitter.setSplitAtPage(end - start + 1)
                                val documents = splitter.split(source)
                                try {
                                    val document = documents.firstOrNull()
                                        ?: error("No pages found for Split ${index + 1}.")
                                    try {
                                        val bytes = ByteArrayOutputStream().use { output ->
                                            document.save(output)
                                            output.toByteArray()
                                        }
                                        SplitPdfOutput(
                                            fileName = "${baseName}_split_${index + 1}_pages_${start}-${end}.pdf",
                                            bytes = bytes
                                        )
                                    } finally {
                                        documents.forEach { it.close() }
                                    }
                                } catch (error: Exception) {
                                    documents.forEach { runCatching { it.close() } }
                                    throw error
                                }
                            }
                        } finally {
                            source.close()
                        }
                    }
                }
                outputQueue = outputs
                totalOutputs = outputs.size
                savedOutputs = 0
                status = if (outputs.isEmpty()) "No split files were created."
                else "Choose where to save Split 1 of ${outputs.size}."
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
                    Text("Choose custom page ranges for each PDF", color = SplitPdfMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SplitPdfSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking && outputQueue.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SplitPdfAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages", color = SplitPdfAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Custom split ranges", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Each range becomes a separate PDF", color = SplitPdfMuted, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { addRange() },
                    enabled = pageCount > 0 && !isWorking && outputQueue.isEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("+ Add Split", color = SplitPdfAccent, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ranges.forEachIndexed { index, range ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SplitPdfSurface)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Split ${index + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                if (ranges.size > 1) {
                                    Text(
                                        "Remove",
                                        color = SplitPdfAccent,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable(enabled = !isWorking && outputQueue.isEmpty()) {
                                            ranges = ranges.filterNot { it.id == range.id }
                                        }.padding(4.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Start page", color = SplitPdfMuted, fontSize = 11.sp)
                                    OutlinedTextField(
                                        value = range.start,
                                        onValueChange = { updateRange(range.id, start = it.filter(Char::isDigit)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = pageCount > 0 && !isWorking && outputQueue.isEmpty(),
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
                                    Text("End page", color = SplitPdfMuted, fontSize = 11.sp)
                                    OutlinedTextField(
                                        value = range.end,
                                        onValueChange = { updateRange(range.id, end = it.filter(Char::isDigit)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        enabled = pageCount > 0 && !isWorking && outputQueue.isEmpty(),
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
                        }
                    }
                }
                if (pageCount == 0) {
                    Text("Select a PDF first to configure its split ranges.", color = SplitPdfMuted, fontSize = 12.sp)
                }
            }

            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status!!, color = SplitPdfMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
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
                    Text("Create ${ranges.size} PDF file(s)", color = Color.White, fontWeight = FontWeight.Bold)
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

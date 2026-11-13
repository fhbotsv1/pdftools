package com.littlebird.pdftoolsapp

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream

private val MergePdfBackground = Color(0xFF101522)
private val MergePdfSurface = Color(0xFF1B2433)
private val MergePdfAccent = Color(0xFFEF6262)
private val MergePdfMuted = Color(0xFF9AA9BD)

private data class MergePdfFile(val uri: Uri, val name: String)

@Composable
fun MergePdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFiles by remember { mutableStateOf<List<MergePdfFile>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val bytes = pendingPdf
        if (destination != null && bytes != null) {
            scope.launch {
                isWorking = true
                status = "Saving merged PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "PDF files merged and saved successfully."
                } catch (error: Exception) {
                    status = "Could not save PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                    pendingPdf = null
                }
            }
        } else {
            pendingPdf = null
            if (destination == null) status = "Save cancelled."
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val additions = uris.mapNotNull { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Temporary read access is still valid for this screen session.
                }
                val name = getMergePdfDisplayName(context, uri)
                MergePdfFile(uri, name)
            }
            selectedFiles = (selectedFiles + additions)
                .distinctBy { it.uri }
            status = null
        }
    }

    fun mergeFiles() {
        if (selectedFiles.size < 2 || isWorking) return
        isWorking = true
        status = "Merging ${selectedFiles.size} PDF files…"
        val filesToMerge = selectedFiles.toList()
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val merged = PDDocument()
                    try {
                        val merger = PDFMergerUtility()
                        filesToMerge.forEach { file ->
                            val input = context.contentResolver.openInputStream(file.uri)
                                ?: error("Could not open ${file.name}")
                            input.use { stream ->
                                val source = PDDocument.load(stream)
                                try {
                                    merger.appendDocument(merged, source)
                                } finally {
                                    source.close()
                                }
                            }
                        }
                        ByteArrayOutputStream().use { output ->
                            merged.save(output)
                            output.toByteArray()
                        }
                    } finally {
                        merged.close()
                    }
                }
                pendingPdf = bytes
                isWorking = false
                status = "Choose where to save the merged PDF."
                saveLauncher.launch("Merged_document.pdf")
            } catch (error: Exception) {
                isWorking = false
                status = "Could not merge PDFs: ${error.localizedMessage ?: "Check that every file is a valid, unlocked PDF."}"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = MergePdfAccent, surface = MergePdfSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(MergePdfBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Merge PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Combine multiple PDF files into one", color = MergePdfMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MergePdfSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Select PDF files", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Choose two or more PDFs. The files will be combined in the order shown below, on this device.",
                        color = MergePdfMuted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MergePdfAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (selectedFiles.isEmpty()) "Select PDF files" else "Add more PDF files", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Selected files", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${selectedFiles.size} selected", color = MergePdfMuted, fontSize = 12.sp)
                if (selectedFiles.isNotEmpty()) {
                    TextButton(onClick = { selectedFiles = emptyList(); status = null }, enabled = !isWorking) {
                        Text("Clear", color = MergePdfAccent)
                    }
                }
            }

            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MergePdfSurface)
            ) {
                if (selectedFiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Your selected PDF files will appear here.", color = MergePdfMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(selectedFiles, key = { _, file -> file.uri.toString() }) { index, file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(Color(0xFF263246), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}", color = MergePdfAccent, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                                Text(file.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("↑", color = if (index > 0) Color.White else MergePdfMuted, modifier = Modifier.clickable(enabled = index > 0 && !isWorking) {
                                        selectedFiles = selectedFiles.toMutableList().also { list ->
                                            val item = list.removeAt(index)
                                            list.add(index - 1, item)
                                        }
                                    }.padding(horizontal = 8.dp, vertical = 2.dp))
                                    Text("↓", color = if (index < selectedFiles.lastIndex) Color.White else MergePdfMuted, modifier = Modifier.clickable(enabled = index < selectedFiles.lastIndex && !isWorking) {
                                        selectedFiles = selectedFiles.toMutableList().also { list ->
                                            val item = list.removeAt(index)
                                            list.add(index + 1, item)
                                        }
                                    }.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                                Text("×", color = MergePdfAccent, fontSize = 22.sp, modifier = Modifier.clickable(enabled = !isWorking) {
                                    selectedFiles = selectedFiles.filterIndexed { i, _ -> i != index }
                                }.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Text(status!!, color = MergePdfMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { mergeFiles() },
                enabled = selectedFiles.size >= 2 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MergePdfAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Merge PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getMergePdfDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

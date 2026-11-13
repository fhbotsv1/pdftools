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

private val DeletePageBackground = Color(0xFF101522)
private val DeletePageSurface = Color(0xFF1B2433)
private val DeletePageAccent = Color(0xFFEF6262)
private val DeletePageMuted = Color(0xFF9AA9BD)

@Composable
fun DeletePagesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val uri = sourceUri
        val pagesToDelete = selectedPages.toSet()
        if (destination != null && uri != null && pagesToDelete.isNotEmpty()) {
            scope.launch {
                isWorking = true
                status = "Deleting selected pages…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        val bytes = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                require(pagesToDelete.size < document.numberOfPages) {
                                    "You cannot delete every page. Keep at least one page."
                                }
                                pagesToDelete.sortedDescending().forEach { index ->
                                    document.removePage(index)
                                }
                                ByteArrayOutputStream().use { output ->
                                    document.save(output)
                                    output.toByteArray()
                                }
                            }
                        }
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Updated PDF saved successfully. Original file is unchanged."
                    selectedPages = emptySet()
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
                    val count = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { it.numberOfPages }
                        }
                    }
                    require(count > 0) { "The selected PDF has no pages." }
                    sourceUri = uri
                    sourceName = getDeletePagesDisplayName(context, uri)
                    pageCount = count
                    selectedPages = emptySet()
                    status = "Select the pages you want to remove."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pageCount = 0
                    selectedPages = emptySet()
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = DeletePageAccent, surface = DeletePageSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(DeletePageBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Delete Pages", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Choose individual pages to remove from a PDF", color = DeletePageMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DeletePageSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DeletePageAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages", color = DeletePageAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Select pages to delete", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${selectedPages.size} of $pageCount selected", color = DeletePageMuted, fontSize = 11.sp)
                }
                TextButton(
                    onClick = {
                        selectedPages = if (selectedPages.size == pageCount) emptySet() else (0 until pageCount).toSet()
                        status = null
                    },
                    enabled = pageCount > 0 && !isWorking
                ) {
                    Text(if (selectedPages.size == pageCount) "Clear all" else "Select all", color = DeletePageAccent)
                }
            }

            if (pageCount == 0) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Select a PDF to display its pages.", color = DeletePageMuted, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (0 until pageCount).forEach { index ->
                        val checked = index in selectedPages
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isWorking) {
                                selectedPages = if (checked) selectedPages - index else selectedPages + index
                                status = null
                            },
                            shape = RoundedCornerShape(13.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (checked) Color(0xFF3A2933) else DeletePageSurface
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        selectedPages = if (value) selectedPages + index else selectedPages - index
                                        status = null
                                    },
                                    enabled = !isWorking,
                                    colors = CheckboxDefaults.colors(checkedColor = DeletePageAccent)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("Page ${index + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(if (checked) "Will be removed" else "Keep this page", color = DeletePageMuted, fontSize = 11.sp)
                                }
                                Text(if (checked) "✕" else "▤", color = if (checked) DeletePageAccent else DeletePageMuted, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status!!, color = DeletePageMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (selectedPages.isEmpty()) {
                        status = "Select at least one page to delete."
                    } else if (selectedPages.size >= pageCount) {
                        status = "At least one page must remain in the PDF."
                    } else {
                        val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                        saveLauncher.launch("${baseName}_pages_deleted.pdf")
                    }
                },
                enabled = sourceUri != null && selectedPages.isNotEmpty() && selectedPages.size < pageCount && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeletePageAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Delete Selected Pages & Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getDeletePagesDisplayName(context: Context, uri: Uri): String {
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

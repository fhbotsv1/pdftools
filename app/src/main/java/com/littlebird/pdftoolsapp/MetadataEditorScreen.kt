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
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val MetadataBackground = Color(0xFF101522)
private val MetadataSurface = Color(0xFF1B2433)
private val MetadataAccent = Color(0xFFEF6262)
private val MetadataMuted = Color(0xFF9AA9BD)

@Composable
fun MetadataEditorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
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
                status = "Saving updated PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Updated PDF saved successfully. The original is unchanged."
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
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getMetadataPdfDisplayName(context, uri)
            isWorking = true
            status = "Reading PDF metadata…"
            scope.launch {
                try {
                    val details = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                val info = document.documentInformation
                                listOf(
                                    info.title.orEmpty(),
                                    info.author.orEmpty(),
                                    info.subject.orEmpty(),
                                    info.keywords.orEmpty()
                                )
                            }
                        }
                    }
                    title = details[0]
                    author = details[1]
                    subject = details[2]
                    keywords = details[3]
                    status = "Metadata loaded. Edit the fields below or remove all metadata."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    status = "Could not read this PDF: ${error.localizedMessage ?: "The file may be damaged or password-protected."}"
                } finally {
                    isWorking = false
                }
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary read access is sufficient for this session.
            }
        }
    }

    fun processPdf(removeAll: Boolean) {
        val uri = sourceUri ?: return
        if (isWorking) return
        isWorking = true
        status = if (removeAll) "Removing PDF metadata…" else "Updating PDF metadata…"
        val newTitle = title
        val newAuthor = author
        val newSubject = subject
        val newKeywords = keywords
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            if (removeAll) {
                                document.documentInformation = PDDocumentInformation()
                                document.documentCatalog.setMetadata(null)
                            } else {
                                val info = document.documentInformation
                                info.setTitle(newTitle.trim())
                                info.setAuthor(newAuthor.trim())
                                info.setSubject(newSubject.trim())
                                info.setKeywords(newKeywords.trim())
                            }
                            ByteArrayOutputStream().use { output ->
                                document.save(output)
                                output.toByteArray()
                            }
                        }
                    }
                }
                pendingPdf = bytes
                isWorking = false
                status = if (removeAll) {
                    "Metadata removed. Choose where to save the clean copy."
                } else {
                    "Metadata updated. Choose where to save the new PDF."
                }
                val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                saveLauncher.launch(if (removeAll) "${baseName}_metadata_removed.pdf" else "${baseName}_metadata_edited.pdf")
            } catch (error: Exception) {
                isWorking = false
                status = "Could not process this PDF: ${error.localizedMessage ?: "Check that the PDF is valid and is not password-protected."}"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = MetadataAccent, surface = MetadataSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(MetadataBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("Metadata Editor", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Edit document details or remove metadata", color = MetadataMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MetadataSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MetadataAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF",
                            color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MetadataSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Document information", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("These details are stored inside the PDF file and may be visible in document properties.",
                        color = MetadataMuted, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    MetadataTextField("Title", title, { title = it }, !isWorking)
                    Spacer(Modifier.height(10.dp))
                    MetadataTextField("Author", author, { author = it }, !isWorking)
                    Spacer(Modifier.height(10.dp))
                    MetadataTextField("Subject", subject, { subject = it }, !isWorking)
                    Spacer(Modifier.height(10.dp))
                    MetadataTextField("Keywords (separate with commas)", keywords, { keywords = it }, !isWorking)
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263246)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("About metadata removal", color = MetadataAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Remove All clears the standard PDF document information and the embedded XMP metadata packet. It does not erase visible text, images, comments or other document content.",
                        color = MetadataMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status!!, color = MetadataMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { processPdf(false) },
                enabled = sourceUri != null && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MetadataAccent,
                    disabledContainerColor = Color(0xFF51414A)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Save Edited Metadata", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = { processPdf(true) },
                enabled = sourceUri != null && !isWorking,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MetadataAccent)
            ) {
                Text("Remove All Metadata", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MetadataTextField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = false,
        minLines = 1,
        maxLines = 3,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = MetadataAccent,
            unfocusedBorderColor = Color(0xFF526075),
            focusedLabelColor = MetadataAccent,
            unfocusedLabelColor = MetadataMuted,
            cursorColor = MetadataAccent
        )
    )
}

private fun getMetadataPdfDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val RemovePasswordBackground = Color(0xFF101522)
private val RemovePasswordSurface = Color(0xFF1B2433)
private val RemovePasswordAccent = Color(0xFFEF6262)
private val RemovePasswordMuted = Color(0xFF9AA9BD)

@Composable
fun RemovePasswordPdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
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
                status = "Saving unlocked PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Password protection removed. Unlocked PDF saved successfully."
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
            sourceName = getRemovePasswordPdfDisplayName(context, uri)
            password = ""
            status = "PDF selected. Enter its current password."
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary read access is sufficient for this session.
            }
        }
    }

    fun removePassword() {
        val uri = sourceUri ?: return
        if (isWorking) return

        val enteredPassword = password
        isWorking = true
        status = "Checking password and removing protection…"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream, enteredPassword).use { document ->
                            if (!document.isEncrypted) {
                                error("This PDF does not have password protection.")
                            }
                            document.setAllSecurityToBeRemoved(true)
                            ByteArrayOutputStream().use { output ->
                                document.save(output)
                                output.toByteArray()
                            }
                        }
                    }
                }
                pendingPdf = bytes
                isWorking = false
                status = "Password protection removed. Choose where to save the unlocked PDF."
                val originalName = sourceName ?: "Document.pdf"
                val baseName = originalName.substringBeforeLast('.', originalName)
                saveLauncher.launch("${baseName}_unlocked.pdf")
            } catch (error: Exception) {
                isWorking = false
                val detail = error.localizedMessage ?: "Unknown error"
                status = if (
                    detail.contains("password", ignoreCase = true) ||
                    error.javaClass.simpleName.contains("InvalidPassword", ignoreCase = true)
                ) {
                    "Could not unlock this PDF. Check the current password and try again."
                } else {
                    "Could not remove protection: $detail"
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = RemovePasswordAccent, surface = RemovePasswordSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(RemovePasswordBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Remove Password", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Unlock a PDF using its current password", color = RemovePasswordMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = RemovePasswordSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Choose a protected PDF", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = RemovePasswordAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = RemovePasswordSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Enter current password", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Enter the password that currently opens this PDF. The app uses it only to unlock the selected file.",
                        color = RemovePasswordMuted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Current password") },
                        singleLine = true,
                        enabled = !isWorking,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                if (showPassword) "Hide" else "Show",
                                color = RemovePasswordAccent,
                                modifier = Modifier.clickable { showPassword = !showPassword }.padding(8.dp)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = RemovePasswordAccent,
                            unfocusedBorderColor = Color(0xFF526075),
                            focusedLabelColor = RemovePasswordAccent,
                            unfocusedLabelColor = RemovePasswordMuted,
                            cursorColor = RemovePasswordAccent
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263246))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Important", color = RemovePasswordAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "You must know the PDF's current password. This tool does not crack or recover forgotten passwords. The unlocked copy is saved as a new file; your original PDF is not changed. Processing happens on this device.",
                        color = RemovePasswordMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status!!, color = RemovePasswordMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { removePassword() },
                enabled = sourceUri != null && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RemovePasswordAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Remove Password and Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getRemovePasswordPdfDisplayName(context: Context, uri: Uri): String {
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

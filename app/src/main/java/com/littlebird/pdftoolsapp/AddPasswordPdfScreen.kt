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
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import android.util.Base64

private val AddPasswordBackground = Color(0xFF101522)
private val AddPasswordSurface = Color(0xFF1B2433)
private val AddPasswordAccent = Color(0xFFEF6262)
private val AddPasswordMuted = Color(0xFF9AA9BD)

@Composable
fun AddPasswordPdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
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
                status = "Saving password-protected PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Password-protected PDF saved successfully."
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
            sourceName = getAddPasswordPdfDisplayName(context, uri)
            status = "PDF selected. Enter a password to protect it."
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary read access is sufficient for this session.
            }
        }
    }

    fun protectPdf() {
        val uri = sourceUri ?: return
        if (isWorking) return
        if (password.length < 6) {
            status = "Use a password with at least 6 characters."
            return
        }
        if (password != confirmPassword) {
            status = "The passwords do not match. Please check and try again."
            return
        }

        val enteredPassword = password
        isWorking = true
        status = "Applying password protection…"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            val permissions = AccessPermission().apply {
                                setCanPrint(true)
                                setCanModify(true)
                                setCanExtractContent(true)
                                setCanModifyAnnotations(true)
                                setCanFillInForm(true)
                                setCanExtractForAccessibility(true)
                                setCanAssembleDocument(true)
                                setCanPrintDegraded(true)
                            }
                            val ownerPassword = generateAddPasswordOwnerPassword()
                            val policy = StandardProtectionPolicy(
                                ownerPassword,
                                enteredPassword,
                                permissions
                            ).apply {
                                setEncryptionKeyLength(128)
                                setPreferAES(true)
                            }
                            document.protect(policy)
                            ByteArrayOutputStream().use { output ->
                                document.save(output)
                                output.toByteArray()
                            }
                        }
                    }
                }
                pendingPdf = bytes
                isWorking = false
                status = "Protection applied. Choose where to save the new PDF."
                val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                saveLauncher.launch("${baseName}_protected.pdf")
            } catch (error: Exception) {
                isWorking = false
                status = "Could not protect this PDF: ${error.localizedMessage ?: "Check that the PDF is valid and is not already password-protected."}"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AddPasswordAccent, surface = AddPasswordSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(AddPasswordBackground)
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
                    Text("Add Password", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Protect a PDF from being opened without a password", color = AddPasswordMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AddPasswordSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AddPasswordAccent),
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
                colors = CardDefaults.cardColors(containerColor = AddPasswordSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Set opening password", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This password will be required whenever someone opens the protected PDF. Use at least 6 characters.",
                        color = AddPasswordMuted, fontSize = 12.sp, lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("New password") },
                        singleLine = true,
                        enabled = !isWorking,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                if (showPassword) "Hide" else "Show",
                                color = AddPasswordAccent,
                                modifier = Modifier.clickable { showPassword = !showPassword }.padding(8.dp)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AddPasswordAccent,
                            unfocusedBorderColor = Color(0xFF526075),
                            focusedLabelColor = AddPasswordAccent,
                            unfocusedLabelColor = AddPasswordMuted,
                            cursorColor = AddPasswordAccent
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm password") },
                        singleLine = true,
                        enabled = !isWorking,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AddPasswordAccent,
                            unfocusedBorderColor = Color(0xFF526075),
                            focusedLabelColor = AddPasswordAccent,
                            unfocusedLabelColor = AddPasswordMuted,
                            cursorColor = AddPasswordAccent
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
                    Text("Privacy and compatibility", color = AddPasswordAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "The PDF is encrypted on this device using 128-bit AES. The original file is not changed. Keep your password safe; this app does not store it.",
                        color = AddPasswordMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status!!, color = AddPasswordMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { protectPdf() },
                enabled = sourceUri != null && password.isNotEmpty() && confirmPassword.isNotEmpty() && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AddPasswordAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Protect PDF and Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun generateAddPasswordOwnerPassword(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun getAddPasswordPdfDisplayName(context: Context, uri: Uri): String {
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

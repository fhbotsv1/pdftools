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
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val FillFormsBackground = Color(0xFF101522)
private val FillFormsSurface = Color(0xFF1B2433)
private val FillFormsAccent = Color(0xFFEF6262)
private val FillFormsMuted = Color(0xFF9AA9BD)

private data class PdfFormFieldInfo(
    val name: String,
    val label: String,
    val type: String,
    val value: String,
    val options: List<String>,
    val readOnly: Boolean
)

@Composable
fun FillFormsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var fields by remember { mutableStateOf<List<PdfFormFieldInfo>>(emptyList()) }
    val values = remember { mutableStateMapOf<String, String>() }
    var flattenForm by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val bytes = pendingPdf
        if (destination == null) {
            pendingPdf = null
            status = "Save cancelled."
        } else if (bytes == null) {
            status = "There is no completed PDF to save."
        } else {
            scope.launch {
                isWorking = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Filled PDF saved successfully. Your original file is unchanged."
                } catch (error: Exception) {
                    status = "Could not save PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                    pendingPdf = null
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getFillFormsDisplayName(context, uri)
            fields = emptyList()
            values.clear()
            status = "Reading interactive form fields…"
            scope.launch {
                isWorking = true
                try {
                    val loadedFields = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                val form = document.documentCatalog.acroForm
                                    ?: error("This PDF does not contain an interactive form (AcroForm).")
                                val result = mutableListOf<PdfFormFieldInfo>()
                                form.fieldTree.forEach { field ->
                                    if (field !is PDNonTerminalField) {
                                        val name = field.fullyQualifiedName
                                            ?: field.partialName
                                            ?: "field_${result.size + 1}"
                                        val type = when (field) {
                                            is PDTextField -> "Text"
                                            is PDCheckBox -> "Checkbox"
                                            is PDRadioButton -> "Radio"
                                            is PDChoice -> "Choice"
                                            else -> "Unsupported"
                                        }
                                        val value = when (field) {
                                            is PDCheckBox -> field.isChecked().toString()
                                            else -> field.valueAsString ?: ""
                                        }
                                        val options = when (field) {
                                            is PDChoice -> field.options ?: emptyList()
                                            is PDRadioButton -> field.onValues ?: emptyList()
                                            else -> emptyList()
                                        }
                                        result.add(
                                            PdfFormFieldInfo(
                                                name = name,
                                                label = field.partialName ?: name.substringAfterLast('.'),
                                                type = type,
                                                value = value,
                                                options = options.toList(),
                                                readOnly = field.isReadOnly
                                            )
                                        )
                                    }
                                }
                                result
                            }
                        }
                    }
                    fields = loadedFields
                    loadedFields.forEach { values[it.name] = it.value }
                    status = if (loadedFields.isEmpty()) {
                        "No editable form fields were found in this PDF."
                    } else {
                        "${loadedFields.size} form field(s) found. Enter your information below."
                    }
                } catch (error: Exception) {
                    status = "Could not read form: ${error.localizedMessage ?: "This may be a scanned or non-interactive PDF."}"
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

    fun fillAndSave() {
        val uri = sourceUri ?: return
        if (isWorking) return
        if (fields.none { !it.readOnly && it.type != "Unsupported" }) {
            status = "There are no supported editable fields to fill."
            return
        }
        val enteredValues = fields.associate { it.name to (values[it.name] ?: it.value) }
        val flatten = flattenForm
        val baseName = (sourceName ?: "document").substringBeforeLast('.', sourceName ?: "document")
        scope.launch {
            isWorking = true
            status = "Filling PDF form fields…"
            try {
                val outputBytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            val form = document.documentCatalog.acroForm
                                ?: error("This PDF does not contain an interactive form.")
                            fields.forEach { info ->
                                if (!info.readOnly) {
                                    val field = form.getField(info.name) ?: return@forEach
                                    val value = enteredValues[info.name] ?: ""
                                    when (field) {
                                        is PDTextField -> field.setValue(value)
                                        is PDCheckBox -> if (value.toBoolean()) field.check() else field.unCheck()
                                        is PDRadioButton -> if (value.isNotBlank()) field.setValue(value)
                                        is PDChoice -> if (value.isNotBlank()) field.setValue(value) else field.setValue("")
                                    }
                                }
                            }
                            form.refreshAppearances()
                            if (flatten) form.flatten()
                            ByteArrayOutputStream().use { output ->
                                document.save(output)
                                output.toByteArray()
                            }
                        }
                    }
                }
                pendingPdf = outputBytes
                status = "Form completed. Choose where to save the new PDF."
                saveLauncher.launch("${baseName}_filled.pdf")
            } catch (error: Exception) {
                status = "Could not fill this PDF: ${error.localizedMessage ?: "Check the form fields and try again."}"
            } finally {
                isWorking = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = FillFormsAccent, surface = FillFormsSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(FillFormsBackground)
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
                    Text("Fill Forms", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Complete interactive PDF form fields", color = FillFormsMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = FillFormsSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF form", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = FillFormsAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(7.dp))
                        Text(sourceName!!, color = FillFormsMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${fields.size} field(s) detected", color = FillFormsAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263246))
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Supported form controls", color = Color(0xFFFFB4AB), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Text fields, checkboxes, radio buttons and dropdown/list choices are supported. Scanned forms and ordinary page text are not interactive fields; use a PDF editor or annotation tool for those.",
                        color = FillFormsMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            if (fields.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Form fields", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Review each value before saving.", color = FillFormsMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                fields.forEach { field ->
                    FormFieldEditor(
                        field = field,
                        value = values[field.name] ?: field.value,
                        onValueChange = { values[field.name] = it },
                        enabled = !isWorking
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = FillFormsSurface)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = flattenForm,
                            onCheckedChange = { flattenForm = it },
                            enabled = !isWorking,
                            colors = CheckboxDefaults.colors(checkedColor = FillFormsAccent)
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Flatten form after filling", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Makes filled fields non-editable in the saved PDF.", color = FillFormsMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status!!, color = FillFormsMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { fillAndSave() },
                enabled = sourceUri != null && fields.any { !it.readOnly && it.type != "Unsupported" } && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FillFormsAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Processing…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Fill Form and Save PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FormFieldEditor(
    field: PdfFormFieldInfo,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember(field.name) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = FillFormsSurface)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(field.type, color = FillFormsAccent, fontSize = 10.sp)
            }
            Spacer(Modifier.height(7.dp))
            when {
                field.readOnly -> Text(value.ifBlank { "Read-only field" }, color = FillFormsMuted, fontSize = 12.sp)
                field.type == "Checkbox" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = value.toBoolean(),
                            onCheckedChange = { onValueChange(it.toString()) },
                            enabled = enabled,
                            colors = CheckboxDefaults.colors(checkedColor = FillFormsAccent)
                        )
                        Text(if (value.toBoolean()) "Checked" else "Not checked", color = FillFormsMuted, fontSize = 12.sp)
                    }
                }
                (field.type == "Choice" || field.type == "Radio") && field.options.isNotEmpty() -> {
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text(value.ifBlank { "Choose an option" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("⌄", color = FillFormsAccent)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            field.options.distinct().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.ifBlank { "(blank)" }) },
                                    onClick = {
                                        onValueChange(option)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                field.type == "Text" || field.type == "Choice" || field.type == "Radio" -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = { Text("Enter value") },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = FillFormsAccent,
                            unfocusedBorderColor = Color(0xFF526075),
                            focusedLabelColor = FillFormsAccent,
                            unfocusedLabelColor = FillFormsMuted,
                            cursorColor = FillFormsAccent
                        )
                    )
                }
                else -> Text("This field type is not editable in this version.", color = FillFormsMuted, fontSize = 11.sp)
            }
        }
    }
}

private fun getFillFormsDisplayName(context: Context, uri: Uri): String {
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

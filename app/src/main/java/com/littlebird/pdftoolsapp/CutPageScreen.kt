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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val CutPageBackground = Color(0xFF101522)
private val CutPageSurface = Color(0xFF1B2433)
private val CutPageAccent = Color(0xFFEF6262)
private val CutPageMuted = Color(0xFF9AA9BD)

private data class CropMargins(
    val top: String = "0",
    val bottom: String = "0",
    val left: String = "0",
    val right: String = "0"
)

@Composable
fun CutPageScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var applyToAll by remember { mutableStateOf(true) }
    var sharedMargins by remember { mutableStateOf(CropMargins()) }
    var pageMargins by remember { mutableStateOf<Map<Int, CropMargins>>(emptyMap()) }
    var selectedPage by remember { mutableIntStateOf(0) }
    var pageMenuExpanded by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val currentMargins = if (applyToAll) sharedMargins else pageMargins[selectedPage] ?: CropMargins()

    fun updateMargins(update: (CropMargins) -> CropMargins) {
        if (applyToAll) {
            sharedMargins = update(sharedMargins)
        } else {
            val current = pageMargins[selectedPage] ?: CropMargins()
            pageMargins = pageMargins + (selectedPage to update(current))
        }
        status = null
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val uri = sourceUri
        if (destination != null && uri != null && pageCount > 0) {
            val allMode = applyToAll
            val allMargins = sharedMargins
            val individualMargins = pageMargins.toMap()
            scope.launch {
                isWorking = true
                status = "Applying crop settings…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        val bytes = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                for (index in 0 until document.numberOfPages) {
                                    val margins = if (allMode) allMargins else individualMargins[index] ?: CropMargins()
                                    val top = (margins.top.toFloatOrNull() ?: 0f).coerceAtLeast(0f) * 72f / 25.4f
                                    val bottom = (margins.bottom.toFloatOrNull() ?: 0f).coerceAtLeast(0f) * 72f / 25.4f
                                    val left = (margins.left.toFloatOrNull() ?: 0f).coerceAtLeast(0f) * 72f / 25.4f
                                    val right = (margins.right.toFloatOrNull() ?: 0f).coerceAtLeast(0f) * 72f / 25.4f
                                    val page = document.getPage(index)
                                    val current = page.cropBox
                                    val newWidth = current.width - left - right
                                    val newHeight = current.height - top - bottom
                                    require(newWidth >= 12f && newHeight >= 12f) {
                                        "Crop margins are too large for page ${index + 1}."
                                    }
                                    page.cropBox = PDRectangle(
                                        current.lowerLeftX + left,
                                        current.lowerLeftY + bottom,
                                        newWidth,
                                        newHeight
                                    )
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
                    status = "Cropped PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not crop PDF: ${error.localizedMessage ?: "Unknown error"}"
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
                    sourceName = getCutPageDisplayName(context, uri)
                    pageCount = count
                    applyToAll = true
                    sharedMargins = CropMargins()
                    pageMargins = emptyMap()
                    selectedPage = 0
                    status = "Loaded $count pages. Enter crop margins in millimetres."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    pageCount = 0
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = CutPageAccent, surface = CutPageSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(CutPageBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Cut Page", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Crop page edges using custom margins", color = CutPageMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CutPageSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CutPageAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages", color = CutPageAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CutPageSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Crop settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = applyToAll,
                            onCheckedChange = {
                                applyToAll = it
                                status = null
                            },
                            enabled = pageCount > 0 && !isWorking,
                            colors = CheckboxDefaults.colors(checkedColor = CutPageAccent)
                        )
                        Column {
                            Text("Apply to all pages", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (applyToAll) "Same margins will be used on every page"
                                else "Set different margins for each page",
                                color = CutPageMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (!applyToAll && pageCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Box {
                            OutlinedButton(
                                onClick = { pageMenuExpanded = true },
                                enabled = !isWorking,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Page ${selectedPage + 1}  ▾", color = Color.White)
                            }
                            DropdownMenu(
                                expanded = pageMenuExpanded,
                                onDismissRequest = { pageMenuExpanded = false }
                            ) {
                                (0 until pageCount).forEach { index ->
                                    DropdownMenuItem(
                                        text = { Text("Page ${index + 1}") },
                                        onClick = {
                                            selectedPage = index
                                            pageMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Enter crop margins (mm)", color = CutPageMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CropMarginField("Top", currentMargins.top, !isWorking && pageCount > 0) {
                            updateMargins { it.copy(top = it) }
                        }
                        CropMarginField("Bottom", currentMargins.bottom, !isWorking && pageCount > 0) {
                            updateMargins { it.copy(bottom = it) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CropMarginField("Left", currentMargins.left, !isWorking && pageCount > 0) {
                            updateMargins { it.copy(left = it) }
                        }
                        CropMarginField("Right", currentMargins.right, !isWorking && pageCount > 0) {
                            updateMargins { it.copy(right = it) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "0 mm keeps that edge unchanged. Cropping changes the visible page area; it does not permanently erase hidden PDF content.",
                        color = CutPageMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(status!!, color = CutPageMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    val marginsToCheck = if (applyToAll) listOf(sharedMargins) else pageMargins.values.toList()
                    val hasInvalid = marginsToCheck.any { margins ->
                        listOf(margins.top, margins.bottom, margins.left, margins.right).any {
                            it.toFloatOrNull() == null || it.toFloat() < 0f
                        }
                    }
                    if (hasInvalid) {
                        status = "Enter valid non-negative margin values."
                    } else {
                        val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                        saveLauncher.launch("${baseName}_cropped.pdf")
                    }
                },
                enabled = sourceUri != null && pageCount > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CutPageAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Save Cropped PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RowScope.CropMarginField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(Modifier.weight(1f)) {
        Text(label, color = CutPageMuted, fontSize = 11.sp)
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() || it == '.' }.let { text ->
                    val firstDot = text.indexOf('.')
                    if (firstDot < 0) text else text.substring(0, firstDot + 1) + text.substring(firstDot + 1).replace(".", "")
                }
                onValueChange(filtered)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = CutPageAccent,
                unfocusedBorderColor = CutPageMuted,
                disabledTextColor = CutPageMuted
            )
        )
    }
}

private fun getCutPageDisplayName(context: Context, uri: Uri): String {
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

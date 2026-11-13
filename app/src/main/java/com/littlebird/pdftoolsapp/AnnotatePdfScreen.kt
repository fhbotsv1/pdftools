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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

private val AnnotateBackground = Color(0xFF101522)
private val AnnotateSurface = Color(0xFF1B2433)
private val AnnotateAccent = Color(0xFFEF6262)
private val AnnotateMuted = Color(0xFF9AA9BD)

@Composable
fun AnnotatePdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var addWatermark by remember { mutableStateOf(true) }
    var watermarkText by remember { mutableStateOf("CONFIDENTIAL") }
    var watermarkSize by remember { mutableFloatStateOf(42f) }
    var addPageNumbers by remember { mutableStateOf(false) }
    var pagePrefix by remember { mutableStateOf("Page ") }
    var startingNumber by remember { mutableStateOf("1") }
    var includeTotal by remember { mutableStateOf(true) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val inputUri = sourceUri
        if (destination != null && inputUri != null) {
            val watermarkEnabled = addWatermark
            val chosenWatermark = watermarkText.trim()
            val chosenSize = watermarkSize
            val numbersEnabled = addPageNumbers
            val chosenPrefix = pagePrefix
            val firstNumber = startingNumber.toIntOrNull()
            val showTotal = includeTotal

            if (watermarkEnabled && chosenWatermark.isBlank()) {
                status = "Enter watermark text, or turn off the watermark option."
                return@rememberLauncherForActivityResult
            }
            if (numbersEnabled && firstNumber == null) {
                status = "Enter a valid starting page number."
                return@rememberLauncherForActivityResult
            }

            scope.launch {
                isWorking = true
                status = "Adding annotations…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(inputUri)
                            ?: error("Could not open the selected PDF.")

                        val outputBytes = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                val totalPages = document.numberOfPages
                                if (totalPages == 0) error("The selected PDF has no pages.")

                                document.pages.forEachIndexed { index, page ->
                                    if (watermarkEnabled) {
                                        addWatermarkToPage(document, page, chosenWatermark, chosenSize)
                                    }
                                    if (numbersEnabled) {
                                        val number = firstNumber!! + index
                                        val label = if (showTotal) {
                                            "$chosenPrefix$number of $totalPages"
                                        } else {
                                            "$chosenPrefix$number"
                                        }
                                        addPageNumberToPage(document, page, label)
                                    }
                                }

                                ByteArrayOutputStream().use { output ->
                                    document.save(output)
                                    output.toByteArray()
                                }
                            }
                        }

                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(outputBytes) }
                    }
                    status = "Annotated PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not annotate PDF: ${error.localizedMessage ?: "Check that the PDF is valid and not password-protected."}"
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
                    val pageCount = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { it.numberOfPages }
                        }
                    }
                    if (pageCount <= 0) error("The selected PDF has no pages.")
                    sourceUri = uri
                    sourceName = getAnnotatePdfDisplayName(context, uri)
                    status = "Loaded $pageCount pages. Choose the annotations to add."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that the file is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = AnnotateAccent, surface = AnnotateSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(AnnotateBackground)
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
                    Text("Annotate PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Add a watermark and page numbers", color = AnnotateMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AnnotateSurface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { picker.launch(arrayOf("application/pdf")) },
                            enabled = !isWorking,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AnnotateAccent),
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

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AnnotateSurface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Add watermark", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("Place text diagonally across each page", color = AnnotateMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = addWatermark,
                                onCheckedChange = { addWatermark = it },
                                enabled = !isWorking
                            )
                        }
                        if (addWatermark) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = watermarkText,
                                onValueChange = { watermarkText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Watermark text") },
                                singleLine = true,
                                enabled = !isWorking,
                                colors = annotateTextFieldColors()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Text size: ${watermarkSize.toInt()} pt", color = AnnotateMuted, fontSize = 12.sp)
                            Slider(
                                value = watermarkSize,
                                onValueChange = { watermarkSize = it },
                                valueRange = 18f..72f,
                                enabled = !isWorking,
                                colors = SliderDefaults.colors(thumbColor = AnnotateAccent, activeTrackColor = AnnotateAccent)
                            )
                            Text("The watermark is added in light grey at the centre of every page.", color = AnnotateMuted, fontSize = 11.sp)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AnnotateSurface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Add page numbers", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("Place numbering at the bottom centre", color = AnnotateMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = addPageNumbers,
                                onCheckedChange = { addPageNumbers = it },
                                enabled = !isWorking
                            )
                        }
                        if (addPageNumbers) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pagePrefix,
                                onValueChange = { pagePrefix = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Text before number") },
                                singleLine = true,
                                enabled = !isWorking,
                                colors = annotateTextFieldColors()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = startingNumber,
                                onValueChange = { startingNumber = it.filter(Char::isDigit).take(6) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Starting number") },
                                singleLine = true,
                                enabled = !isWorking,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = annotateTextFieldColors()
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = includeTotal,
                                    onCheckedChange = { includeTotal = it },
                                    enabled = !isWorking,
                                    colors = CheckboxDefaults.colors(checkedColor = AnnotateAccent)
                                )
                                Text("Include total page count (e.g. Page 1 of 10)", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF302A32))
                ) {
                    Text(
                        "Annotations are added to every page. The original PDF is not changed. Use basic English/Latin characters for watermark and page-number text.",
                        color = Color(0xFFFFD7D2),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status!!, color = AnnotateMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val name = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                    saveLauncher.launch("${name}_annotated.pdf")
                },
                enabled = sourceUri != null && (addWatermark || addPageNumbers) && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnnotateAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Save Annotated PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun annotateTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = AnnotateAccent,
    unfocusedBorderColor = AnnotateMuted,
    focusedLabelColor = AnnotateAccent,
    unfocusedLabelColor = AnnotateMuted,
    cursorColor = AnnotateAccent
)

private fun addWatermarkToPage(
    document: PDDocument,
    page: PDPage,
    text: String,
    fontSize: Float
) {
    val font = PDType1Font.HELVETICA_BOLD
    val safeText = text.replace(Regex("[^\\x20-\\x7E]"), "?")
    val textWidth = font.getStringWidth(safeText) / 1000f * fontSize
    val box = page.mediaBox
    val x = max(12f, (box.width - textWidth) / 2f)
    val y = box.height / 2f

    PDPageContentStream(
        document,
        page,
        PDPageContentStream.AppendMode.APPEND,
        true,
        true
    ).use { stream ->
        stream.setNonStrokingColor(190, 190, 190)
        stream.beginText()
        stream.setFont(font, fontSize)
        stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), x, y))
        stream.showText(safeText)
        stream.endText()
    }
}

private fun addPageNumberToPage(
    document: PDDocument,
    page: PDPage,
    text: String
) {
    val font = PDType1Font.HELVETICA
    val fontSize = 10f
    val safeText = text.replace(Regex("[^\\x20-\\x7E]"), "?")
    val textWidth = font.getStringWidth(safeText) / 1000f * fontSize
    val box = page.mediaBox
    val x = max(12f, (box.width - textWidth) / 2f)
    val y = 24f

    PDPageContentStream(
        document,
        page,
        PDPageContentStream.AppendMode.APPEND,
        true,
        true
    ).use { stream ->
        stream.setNonStrokingColor(70, 70, 70)
        stream.beginText()
        stream.setFont(font, fontSize)
        stream.newLineAtOffset(x, y)
        stream.showText(safeText)
        stream.endText()
    }
}

private fun getAnnotatePdfDisplayName(context: Context, uri: Uri): String {
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

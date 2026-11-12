package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

private val ImagePdfBackground = Color(0xFF101522)
private val ImagePdfSurface = Color(0xFF1B2433)
private val ImagePdfAccent = Color(0xFFEF6262)
private val ImagePdfMuted = Color(0xFF9AA9BD)

private data class ImagePdfSettings(
    val pageSize: String = "A4",
    val pageOrientation: String = "Auto",
    val imageOrientation: String = "Auto",
    val scaling: String = "Fit to page",
    val keepAspectRatio: Boolean = true,
    val margins: String = "Small"
)

@Composable
fun ImagesToPdfScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember {
        context.getSharedPreferences("image_to_pdf_preferences", Context.MODE_PRIVATE)
    }
    var hasDefaults by remember { mutableStateOf(preferences.getBoolean("has_defaults", false)) }
    var hasAskedAboutDefaults by remember {
        mutableStateOf(preferences.getBoolean("asked_about_defaults", false))
    }

    var settings by remember { mutableStateOf(readImagePdfSettings(preferences)) }
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showConfiguration by remember { mutableStateOf(false) }
    var showDefaultChoice by remember { mutableStateOf(false) }
    var showFirstSavePrompt by remember { mutableStateOf(false) }
    var updateDefaults by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }

    fun saveSettingsAsDefault(value: ImagePdfSettings) {
        preferences.edit()
            .putBoolean("has_defaults", true)
            .putString("page_size", value.pageSize)
            .putString("page_orientation", value.pageOrientation)
            .putString("image_orientation", value.imageOrientation)
            .putString("scaling", value.scaling)
            .putBoolean("keep_aspect_ratio", value.keepAspectRatio)
            .putString("margins", value.margins)
            .apply()
        hasDefaults = true
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val bytes = pendingPdf
        if (destination != null && bytes != null) {
            scope.launch {
                working = true
                status = "Saving PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "PDF saved successfully."
                } catch (error: Exception) {
                    status = "Could not save PDF: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    working = false
                    pendingPdf = null
                }
            }
        } else if (destination == null) {
            status = "Save cancelled."
            pendingPdf = null
        }
    }

    fun createPdf(chosenSettings: ImagePdfSettings) {
        if (selectedImages.isEmpty() || working) return
        showConfiguration = false
        showDefaultChoice = false
        working = true
        status = "Preparing ${selectedImages.size} image(s)…"
        scope.launch {
            try {
                val pdfBytes = withContext(Dispatchers.IO) {
                    buildImagePdf(context, selectedImages, chosenSettings)
                }
                pendingPdf = pdfBytes
                working = false
                status = "Choose where to save your PDF."
                savePdfLauncher.launch("Images_to_PDF.pdf")
            } catch (error: Exception) {
                working = false
                status = "Could not create PDF: ${error.localizedMessage ?: "Please try different images."}"
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { images ->
        if (images.isNotEmpty()) {
            selectedImages = images
            status = null
            settings = readImagePdfSettings(preferences)
            updateDefaults = false
            if (hasDefaults) showDefaultChoice = true else showConfiguration = true
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = ImagePdfAccent, surface = ImagePdfSurface)) {
        Column(modifier = Modifier.fillMaxSize().background(ImagePdfBackground)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp)
                        .clickableNoRipple { if (showConfiguration) showConfiguration = false else onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Images to PDF", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Create a PDF from your photos", color = ImagePdfMuted, fontSize = 12.sp)
                }
            }

            if (showConfiguration) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp)
                ) {
                    Text(
                        "${selectedImages.size} image(s) selected",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text("All layout options are available together below.", color = ImagePdfMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))

                    SettingsGroup("Paper size", "Choose the sheet size for every page") {
                        ChoiceChips(
                            options = listOf("A4", "A3", "A5", "Letter", "Legal", "Original"),
                            selected = settings.pageSize,
                            onSelected = { settings = settings.copy(pageSize = it) }
                        )
                    }
                    SettingsGroup("Page orientation", "Auto chooses portrait or landscape for each image") {
                        ChoiceChips(
                            options = listOf("Auto", "Portrait", "Landscape"),
                            selected = settings.pageOrientation,
                            onSelected = { settings = settings.copy(pageOrientation = it) }
                        )
                    }
                    SettingsGroup("Image orientation", "Rotate each image before placing it on the page") {
                        ChoiceChips(
                            options = listOf("Auto", "Original", "Portrait", "Landscape"),
                            selected = settings.imageOrientation,
                            onSelected = { settings = settings.copy(imageOrientation = it) }
                        )
                    }
                    SettingsGroup("Image scaling", "Control how each image fits on the paper") {
                        ChoiceChips(
                            options = listOf("Fit to page", "Fit width", "Fill page"),
                            selected = settings.scaling,
                            onSelected = { settings = settings.copy(scaling = it) }
                        )
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ImagePdfSurface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep aspect ratio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Prevent images from looking stretched or squashed", color = ImagePdfMuted, fontSize = 11.sp)
                            }
                            Checkbox(
                                checked = settings.keepAspectRatio,
                                onCheckedChange = { settings = settings.copy(keepAspectRatio = it) },
                                colors = CheckboxDefaults.colors(checkedColor = ImagePdfAccent)
                            )
                        }
                    }
                    SettingsGroup("Page margins", "Space between the image and paper edge") {
                        ChoiceChips(
                            options = listOf("None", "Small", "Normal"),
                            selected = settings.margins,
                            onSelected = { settings = settings.copy(margins = it) }
                        )
                    }

                    if (hasDefaults) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = updateDefaults,
                                onCheckedChange = { updateDefaults = it },
                                colors = CheckboxDefaults.colors(checkedColor = ImagePdfAccent)
                            )
                            Text("Update my saved default settings", color = Color.White, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        if (!hasDefaults && !hasAskedAboutDefaults) {
                            showFirstSavePrompt = true
                        } else {
                            if (updateDefaults) saveSettingsAsDefault(settings)
                            createPdf(settings)
                        }
                    },
                    enabled = !working && selectedImages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ImagePdfAccent),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Text(if (working) "Creating…" else "Create PDF", fontWeight = FontWeight.Bold)
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier.size(104.dp).background(Color(0xFF3A2933), RoundedCornerShape(28.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▧", color = ImagePdfAccent, fontSize = 52.sp)
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Select your images", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choose multiple photos. Each image will be placed on its own PDF page.",
                        color = ImagePdfMuted, fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { imagePicker.launch(arrayOf("image/*")) },
                        enabled = !working,
                        colors = ButtonDefaults.buttonColors(containerColor = ImagePdfAccent),
                        shape = RoundedCornerShape(15.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text("Select images", fontWeight = FontWeight.Bold)
                    }
                    if (selectedImages.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("${selectedImages.size} image(s) ready", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            status?.let {
                Text(
                    it,
                    color = if (it.startsWith("Could not")) Color(0xFFFF9B9B) else ImagePdfMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        if (showDefaultChoice) {
            AlertDialog(
                onDismissRequest = { showDefaultChoice = false; showConfiguration = true },
                containerColor = ImagePdfSurface,
                title = { Text("Use your saved settings?", color = Color.White) },
                text = { Text("You selected ${selectedImages.size} image(s). Use your previous PDF layout or configure it for this file.", color = ImagePdfMuted) },
                confirmButton = {
                    TextButton(onClick = { createPdf(readImagePdfSettings(preferences)) }) {
                        Text("Use defaults", color = ImagePdfAccent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDefaultChoice = false; showConfiguration = true }) {
                        Text("Configure", color = Color.White)
                    }
                }
            )
        }

        if (showFirstSavePrompt) {
            AlertDialog(
                onDismissRequest = {
                    showFirstSavePrompt = false
                    preferences.edit().putBoolean("asked_about_defaults", true).apply()
                    hasAskedAboutDefaults = true
                    createPdf(settings)
                },
                containerColor = ImagePdfSurface,
                title = { Text("Save these settings as default?", color = Color.White) },
                text = { Text("Next time, after selecting images, you can create a PDF immediately with these settings or open Configure to change them.", color = ImagePdfMuted) },
                confirmButton = {
                    TextButton(onClick = {
                        saveSettingsAsDefault(settings)
                        preferences.edit().putBoolean("asked_about_defaults", true).apply()
                        hasAskedAboutDefaults = true
                        showFirstSavePrompt = false
                        createPdf(settings)
                    }) { Text("Save as default", color = ImagePdfAccent) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        preferences.edit().putBoolean("asked_about_defaults", true).apply()
                        hasAskedAboutDefaults = true
                        showFirstSavePrompt = false
                        createPdf(settings)
                    }) { Text("Not now", color = Color.White) }
                }
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = ImagePdfMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ChoiceChips(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                label = { Text(option, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF54343D),
                    selectedLabelColor = Color.White,
                    labelColor = ImagePdfMuted
                )
            )
        }
    }
}

private fun readImagePdfSettings(preferences: android.content.SharedPreferences) = ImagePdfSettings(
    pageSize = preferences.getString("page_size", "A4") ?: "A4",
    pageOrientation = preferences.getString("page_orientation", "Auto") ?: "Auto",
    imageOrientation = preferences.getString("image_orientation", "Auto") ?: "Auto",
    scaling = preferences.getString("scaling", "Fit to page") ?: "Fit to page",
    keepAspectRatio = preferences.getBoolean("keep_aspect_ratio", true),
    margins = preferences.getString("margins", "Small") ?: "Small"
)

private fun buildImagePdf(
    context: Context,
    imageUris: List<Uri>,
    settings: ImagePdfSettings
): ByteArray {
    val document = PdfDocument()
    val output = ByteArrayOutputStream()
    try {
        imageUris.forEachIndexed { index, uri ->
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not read image ${index + 1}.")
            val source = input.use { BitmapFactory.decodeStream(it) }
                ?: error("Image ${index + 1} could not be decoded.")
            val bitmap = orientBitmap(source, settings.imageOrientation, settings.pageOrientation, settings.pageSize)
            if (bitmap !== source) source.recycle()

            try {
                val pageDimensions = getPageDimensions(settings.pageSize, settings.pageOrientation, bitmap)
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageDimensions.first,
                    pageDimensions.second,
                    index + 1
                ).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(AndroidColor.WHITE)

                val margin = when (settings.margins) {
                    "None" -> 0f
                    "Normal" -> 48f
                    else -> 24f
                }
                val content = RectF(
                    margin,
                    margin,
                    pageDimensions.first.toFloat() - margin,
                    pageDimensions.second.toFloat() - margin
                )
                canvas.save()
                canvas.clipRect(content)
                val imageRect = calculateImageRect(bitmap, content, settings.scaling, settings.keepAspectRatio)
                canvas.drawBitmap(bitmap, null, imageRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
                document.finishPage(page)
            } finally {
                bitmap.recycle()
            }
        }
        document.writeTo(output)
        return output.toByteArray()
    } finally {
        document.close()
        output.close()
    }
}

private fun orientBitmap(
    source: Bitmap,
    imageOrientation: String,
    pageOrientation: String,
    pageSize: String
): Bitmap {
    var rotate = false
    when (imageOrientation) {
        "Portrait" -> rotate = source.width > source.height
        "Landscape" -> rotate = source.height > source.width
        "Auto" -> {
            if (pageOrientation == "Portrait") rotate = source.width > source.height
            if (pageOrientation == "Landscape") rotate = source.height > source.width
            if (pageOrientation == "Auto" && pageSize != "Original") rotate = false
        }
    }
    if (!rotate) return source
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun getPageDimensions(pageSize: String, orientation: String, bitmap: Bitmap): Pair<Int, Int> {
    val base = when (pageSize) {
        "A3" -> 842 to 1191
        "A5" -> 420 to 595
        "Letter" -> 612 to 792
        "Legal" -> 612 to 1008
        "Original" -> max(72, bitmap.width * 72 / 160) to max(72, bitmap.height * 72 / 160)
        else -> 595 to 842
    }
    val isLandscape = when (orientation) {
        "Landscape" -> true
        "Portrait" -> false
        else -> bitmap.width >= bitmap.height
    }
    val shouldSwap = if (isLandscape) base.first < base.second else base.first > base.second
    return if (shouldSwap) base.second to base.first else base
}

private fun calculateImageRect(
    bitmap: Bitmap,
    page: RectF,
    scaling: String,
    keepAspectRatio: Boolean
): RectF {
    if (!keepAspectRatio) return RectF(page)
    val widthScale = page.width() / bitmap.width.toFloat()
    val heightScale = page.height() / bitmap.height.toFloat()
    val scale = when (scaling) {
        "Fit width" -> widthScale
        "Fill page" -> max(widthScale, heightScale)
        else -> min(widthScale, heightScale)
    }
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = page.centerX() - width / 2f
    val top = page.centerY() - height / 2f
    return RectF(left, top, left + width, top + height)
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

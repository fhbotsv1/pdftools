package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap

private val CompressBackground = Color(0xFF101522)
private val CompressSurface = Color(0xFF1B2433)
private val CompressAccent = Color(0xFFEF6262)
private val CompressMuted = Color(0xFF9AA9BD)

private data class CompressSettings(
    val compressImages: Boolean = true,
    val imageQuality: Int = 75,
    val maxImageDimension: Int = 2000,
    val removeMetadata: Boolean = false
)

private data class CompressResult(
    val bytes: ByteArray,
    val imagesCompressed: Int
)

private const val COMPRESS_PREFS = "pdf_compress_defaults"

@Composable
fun CompressPdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences(COMPRESS_PREFS, Context.MODE_PRIVATE)
    }

    var settings by remember {
        mutableStateOf(
            CompressSettings(
                compressImages = preferences.getBoolean("compress_images", true),
                imageQuality = preferences.getInt("image_quality", 75).coerceIn(40, 95),
                maxImageDimension = preferences.getInt("max_dimension", 2000).coerceIn(800, 3000),
                removeMetadata = preferences.getBoolean("remove_metadata", false)
            )
        )
    }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val uri = sourceUri
        val chosenSettings = settings
        if (destination != null && uri != null) {
            scope.launch {
                isWorking = true
                status = "Compressing PDF…"
                try {
                    val result = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        val compressed = input.use { stream ->
                            PDDocument.load(stream).use { document ->
                                var imageCount = 0
                                if (chosenSettings.compressImages) {
                                    val visitedForms = Collections.newSetFromMap(
                                        IdentityHashMap<PDFormXObject, Boolean>()
                                    )
                                    for (page in document.pages) {
                                        imageCount += compressResources(
                                            document,
                                            page.resources,
                                            chosenSettings,
                                            visitedForms
                                        )
                                    }
                                }
                                if (chosenSettings.removeMetadata) {
                                    val info = document.documentInformation
                                    info.setTitle(null)
                                    info.setAuthor(null)
                                    info.setSubject(null)
                                    info.setKeywords(null)
                                    info.setCreator(null)
                                    info.setProducer(null)
                                    info.setCreationDate(null)
                                    info.setModificationDate(null)
                                }
                                val bytes = ByteArrayOutputStream().use { output ->
                                    document.save(output)
                                    output.toByteArray()
                                }
                                CompressResult(bytes, imageCount)
                            }
                        }
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(compressed.bytes) }
                        compressed
                    }
                    val savedSize = result.bytes.size / 1024
                    status = "Compressed PDF saved. ${result.imagesCompressed} embedded image(s) optimized. Output: $savedSize KB."
                } catch (error: Exception) {
                    status = "Could not compress PDF: ${error.localizedMessage ?: "Unknown error"}"
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
            sourceUri = uri
            sourceName = getCompressPdfDisplayName(context, uri)
            status = "PDF selected. Choose compression settings and save."
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary access is sufficient for this session.
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = CompressAccent, surface = CompressSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(CompressBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹",
                    color = Color.White,
                    fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
                )
                Column(Modifier.weight(1f)) {
                    Text("Compress PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Reduce file size with adjustable settings", color = CompressMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CompressSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CompressAccent),
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

            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CompressSurface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Compression parameters", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = settings.compressImages,
                                onCheckedChange = { settings = settings.copy(compressImages = it) },
                                enabled = !isWorking,
                                colors = CheckboxDefaults.colors(checkedColor = CompressAccent)
                            )
                            Column {
                                Text("Optimize embedded images", color = Color.White, fontSize = 14.sp)
                                Text("Re-encode supported images to reduce their size", color = CompressMuted, fontSize = 10.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF344052))
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Image quality", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Higher quality keeps more detail but creates larger files", color = CompressMuted, fontSize = 10.sp)
                            }
                            Text("${settings.imageQuality}%", color = CompressAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = settings.imageQuality.toFloat(),
                            onValueChange = { settings = settings.copy(imageQuality = it.toInt()) },
                            valueRange = 40f..95f,
                            steps = 10,
                            enabled = settings.compressImages && !isWorking,
                            colors = SliderDefaults.colors(thumbColor = CompressAccent, activeTrackColor = CompressAccent)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Maximum image dimension", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Images larger than this are resized proportionally", color = CompressMuted, fontSize = 10.sp)
                            }
                            Text("${settings.maxImageDimension} px", color = CompressAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Slider(
                            value = settings.maxImageDimension.toFloat(),
                            onValueChange = { settings = settings.copy(maxImageDimension = (it.toInt() / 100) * 100) },
                            valueRange = 800f..3000f,
                            steps = 21,
                            enabled = settings.compressImages && !isWorking,
                            colors = SliderDefaults.colors(thumbColor = CompressAccent, activeTrackColor = CompressAccent)
                        )
                        HorizontalDivider(color = Color(0xFF344052))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = settings.removeMetadata,
                                onCheckedChange = { settings = settings.copy(removeMetadata = it) },
                                enabled = !isWorking,
                                colors = CheckboxDefaults.colors(checkedColor = CompressAccent)
                            )
                            Column {
                                Text("Remove document metadata", color = Color.White, fontSize = 14.sp)
                                Text("Removes title, author, dates and other document details", color = CompressMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CompressSurface)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Balanced defaults", color = CompressAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "75% image quality and 2000 px maximum dimension are preselected as a balance between file size and visual clarity. These settings do not rasterize pages, so selectable text and vector content are retained.",
                            color = CompressMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                preferences.edit()
                                    .putBoolean("compress_images", settings.compressImages)
                                    .putInt("image_quality", settings.imageQuality)
                                    .putInt("max_dimension", settings.maxImageDimension)
                                    .putBoolean("remove_metadata", settings.removeMetadata)
                                    .apply()
                                status = "Current compression settings saved as your defaults."
                            },
                            enabled = !isWorking,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save these settings as default", color = CompressAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status!!, color = CompressMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                    saveLauncher.launch("${baseName}_compressed.pdf")
                },
                enabled = sourceUri != null && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CompressAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Compress and Save PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun compressResources(
    document: PDDocument,
    resources: PDResources?,
    settings: CompressSettings,
    visitedForms: MutableSet<PDFormXObject>
): Int {
    if (resources == null) return 0
    var compressedCount = 0
    val names = resources.xObjectNames.toList()
    for (name in names) {
        val xObject: PDXObject = try {
            resources.getXObject(name) ?: continue
        } catch (_: Exception) {
            continue
        }
        when (xObject) {
            is PDImageXObject -> {
                val image = xObject
                if (image.isStencil || image.softMask != null ||
                    image.cosObject.containsKey(COSName.MASK) ||
                    image.cosObject.containsKey(COSName.SMASK)
                ) continue

                var bitmap: Bitmap? = null
                try {
                    bitmap = image.image ?: continue
                    val largestSide = maxOf(bitmap.width, bitmap.height)
                    val resized = if (largestSide > settings.maxImageDimension) {
                        val scale = settings.maxImageDimension.toFloat() / largestSide
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else {
                        bitmap
                    }
                    val encoded = ByteArrayOutputStream().use { output ->
                        resized.compress(Bitmap.CompressFormat.JPEG, settings.imageQuality, output)
                        output.toByteArray()
                    }
                    if (resized !== bitmap) resized.recycle()
                    if (encoded.isNotEmpty()) {
                        val replacement = PDImageXObject.createFromByteArray(document, encoded, "compressed-image")
                        resources.put(name, replacement)
                        compressedCount++
                    }
                } catch (_: Exception) {
                    // Keep the original image if this particular image cannot be decoded or encoded.
                } finally {
                    bitmap?.recycle()
                }
            }
            is PDFormXObject -> {
                if (visitedForms.add(xObject)) {
                    compressedCount += compressResources(document, xObject.resources, settings, visitedForms)
                }
            }
        }
    }
    return compressedCount
}

private fun getCompressPdfDisplayName(context: Context, uri: Uri): String {
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

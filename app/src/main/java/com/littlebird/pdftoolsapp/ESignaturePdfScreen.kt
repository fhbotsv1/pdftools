package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val ESignatureBackground = Color(0xFF101522)
private val ESignatureSurface = Color(0xFF1B2433)
private val ESignatureAccent = Color(0xFFEF6262)
private val ESignatureMuted = Color(0xFF9AA9BD)

@Composable
fun ESignaturePdfScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageNumber by remember { mutableStateOf("1") }
    var signatureMode by remember { mutableStateOf("Draw") }
    var typedName by remember { mutableStateOf("") }
    var importedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val strokes = remember { mutableStateListOf<SnapshotStateList<Offset>>() }
    var canvasRevision by remember { mutableIntStateOf(0) }
    var positionX by remember { mutableStateOf("20") }
    var positionY by remember { mutableStateOf("20") }
    var signatureWidth by remember { mutableStateOf("45") }
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
                status = "Saving signed PDF…"
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Signed PDF saved successfully. The original file is unchanged."
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

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getESignatureDisplayName(context, uri)
            pageCount = 0
            pageNumber = "1"
            status = "Reading PDF…"
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream -> PDDocument.load(stream).use { it.numberOfPages } }
                    }
                    pageCount = count
                    status = if (count > 0) "PDF ready. Choose a signature and its placement." else "This PDF has no pages."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    status = "Could not read PDF. It may be password-protected or invalid: ${error.localizedMessage ?: "Unknown error"}"
                }
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Temporary access is enough for this session.
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } ?: error("The selected image could not be decoded.")
                    importedBitmap = bitmap
                    status = "Signature image loaded."
                } catch (error: Exception) {
                    status = "Could not load signature image: ${error.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    fun createSignatureBytes(): ByteArray {
        val bitmap = when (signatureMode) {
            "Draw" -> {
                val result = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(result)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(23, 32, 51)
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                strokes.forEach { points ->
                    if (points.isNotEmpty()) {
                        val path = android.graphics.Path()
                        path.moveTo(points.first().x * 1200f, points.first().y * 400f)
                        points.drop(1).forEach { path.lineTo(it.x * 1200f, it.y * 400f) }
                        if (points.size == 1) {
                            canvas.drawCircle(points.first().x * 1200f, points.first().y * 400f, 5f, paint)
                        } else {
                            canvas.drawPath(path, paint)
                        }
                    }
                }
                result
            }
            "Type" -> {
                val result = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(result)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(23, 32, 51)
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create("cursive", Typeface.NORMAL)
                    textSize = 170f
                }
                val text = typedName.trim()
                val availableWidth = 1100f
                if (paint.measureText(text) > availableWidth) {
                    paint.textSize *= availableWidth / paint.measureText(text)
                }
                canvas.drawText(text, 600f, 250f, paint)
                result
            }
            else -> importedBitmap ?: error("Choose a signature image first.")
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }

    fun signPdf() {
        val uri = sourceUri ?: return
        if (isWorking) return
        val page = pageNumber.toIntOrNull()
        val xMm = positionX.toFloatOrNull()
        val yMm = positionY.toFloatOrNull()
        val widthMm = signatureWidth.toFloatOrNull()
        if (page == null || page !in 1..pageCount) {
            status = "Enter a page number from 1 to $pageCount."
            return
        }
        if (xMm == null || yMm == null || widthMm == null || xMm < 0 || yMm < 0 || widthMm <= 0) {
            status = "Enter valid non-negative placement values and a signature width greater than zero."
            return
        }
        if (signatureMode == "Type" && typedName.isBlank()) {
            status = "Enter the name you want to sign with."
            return
        }
        if (signatureMode == "Draw" && strokes.isEmpty()) {
            status = "Draw your signature in the signature area first."
            return
        }
        if (signatureMode == "Import" && importedBitmap == null) {
            status = "Import a signature image first."
            return
        }

        isWorking = true
        status = "Adding signature to the PDF…"
        scope.launch {
            try {
                val signatureBytes = createSignatureBytes()
                val outputBytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            val targetPage = document.getPage(page - 1)
                            val pageBox = targetPage.mediaBox
                            val widthPoints = widthMm!! * 72f / 25.4f
                            val image = PDImageXObject.createFromByteArray(document, signatureBytes, "signature")
                            val imageHeight = widthPoints * image.height.toFloat() / image.width.toFloat()
                            val xPoints = xMm!! * 72f / 25.4f
                            val yPoints = yMm!! * 72f / 25.4f
                            if (xPoints + widthPoints > pageBox.width || yPoints + imageHeight > pageBox.height) {
                                error("The signature does not fit on this page. Reduce its width or adjust its position.")
                            }
                            PDPageContentStream(
                                document, targetPage, PDPageContentStream.AppendMode.APPEND, true, true
                            ).use { content ->
                                content.drawImage(image, xPoints, yPoints, widthPoints, imageHeight)
                            }
                            ByteArrayOutputStream().use { output ->
                                document.save(output)
                                output.toByteArray()
                            }
                        }
                    }
                }
                pendingPdf = outputBytes
                isWorking = false
                val original = sourceName ?: "Document.pdf"
                val base = original.substringBeforeLast('.', original)
                status = "Signature added. Choose where to save the signed copy."
                saveLauncher.launch("${base}_signed.pdf")
            } catch (error: Exception) {
                isWorking = false
                status = "Could not sign PDF: ${error.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = ESignatureAccent, surface = ESignatureSurface)) {
        Column(
            modifier = modifier.fillMaxSize()
                .background(ESignatureBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("E-Signature", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Add a signature image to a PDF page", color = ESignatureMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ESignatureSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("1. Select PDF", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(9.dp))
                    Button(onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ESignatureAccent),
                        shape = RoundedCornerShape(12.dp)) {
                        Text(if (sourceName == null) "Choose PDF file" else "Choose another PDF",
                            color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(7.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount pages", color = ESignatureMuted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ESignatureSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("2. Create your signature", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Draw", "Type", "Import").forEach { mode ->
                            FilterChip(selected = signatureMode == mode, onClick = {
                                signatureMode = mode
                                status = null
                            }, label = { Text(mode) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF49313A),
                                    selectedLabelColor = Color.White,
                                    labelColor = ESignatureMuted
                                ))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (signatureMode) {
                        "Draw" -> {
                            Text("Sign below with your finger", color = ESignatureMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.fillMaxWidth().height(150.dp)
                                .background(Color.White, RoundedCornerShape(10.dp))) {
                                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { point ->
                                            strokes.add(mutableStateListOf(
                                                Offset((point.x / size.width).coerceIn(0f, 1f),
                                                    (point.y / size.height).coerceIn(0f, 1f))
                                            ))
                                            canvasRevision++
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            strokes.lastOrNull()?.add(
                                                Offset((change.position.x / size.width).coerceIn(0f, 1f),
                                                    (change.position.y / size.height).coerceIn(0f, 1f))
                                            )
                                            canvasRevision++
                                        }
                                    )
                                }) {
                                    canvasRevision.hashCode()
                                    strokes.forEach { points ->
                                        if (points.isNotEmpty()) {
                                            val path = Path().apply {
                                                moveTo(points.first().x * size.width, points.first().y * size.height)
                                                points.drop(1).forEach {
                                                    lineTo(it.x * size.width, it.y * size.height)
                                                }
                                            }
                                            drawPath(path, Color(0xFF172033),
                                                style = Stroke(width = 3.5.dp.toPx()))
                                            if (points.size == 1) {
                                                drawCircle(Color(0xFF172033), radius = 2.dp.toPx(),
                                                    center = Offset(points.first().x * size.width, points.first().y * size.height))
                                            }
                                        }
                                    }
                                }
                                Text("Signature area", color = Color(0x55333333), fontSize = 10.sp,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp))
                            }
                            TextButton(onClick = { strokes.clear(); canvasRevision++ }) {
                                Text("Clear signature", color = ESignatureAccent)
                            }
                        }
                        "Type" -> {
                            OutlinedTextField(value = typedName, onValueChange = { typedName = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Type your name") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = ESignatureAccent, unfocusedBorderColor = Color(0xFF526075),
                                    focusedLabelColor = ESignatureAccent, unfocusedLabelColor = ESignatureMuted))
                            if (typedName.isNotBlank()) {
                                Text("Preview", color = ESignatureMuted, fontSize = 11.sp)
                                Text(typedName, color = Color.White, fontSize = 27.sp,
                                    fontWeight = FontWeight.Normal)
                            }
                        }
                        else -> {
                            Button(onClick = { imagePicker.launch("image/*") }, enabled = !isWorking,
                                colors = ButtonDefaults.buttonColors(containerColor = ESignatureAccent)) {
                                Text(if (importedBitmap == null) "Choose signature image" else "Change signature image",
                                    color = Color.White)
                            }
                            importedBitmap?.let {
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(120.dp)
                                    .background(Color.White, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center) {
                                    androidx.compose.foundation.Image(
                                        bitmap = it.asImageBitmap(), contentDescription = "Imported signature",
                                        modifier = Modifier.fillMaxWidth().height(110.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ESignatureSurface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("3. Position on PDF", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Measurements are in millimetres. X is from the left edge; Y is from the bottom edge.",
                        color = ESignatureMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = pageNumber, onValueChange = { pageNumber = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("PDF page (1–$pageCount)") },
                        singleLine = true,
                        colors = eSignatureFieldColors())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = positionX, onValueChange = { positionX = it },
                            modifier = Modifier.weight(1f), label = { Text("X from left (mm)") },
                            singleLine = true, colors = eSignatureFieldColors())
                        OutlinedTextField(value = positionY, onValueChange = { positionY = it },
                            modifier = Modifier.weight(1f), label = { Text("Y from bottom (mm)") },
                            singleLine = true, colors = eSignatureFieldColors())
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = signatureWidth, onValueChange = { signatureWidth = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Signature width (mm)") },
                        singleLine = true, colors = eSignatureFieldColors())
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263246))) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Important", color = ESignatureAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("This places a signature image on the PDF. It is an electronic signature image, not a certificate-based digital signature; it does not verify identity or prevent later changes to the document. Processing is done on this device.",
                        color = ESignatureMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status!!, color = ESignatureMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { signPdf() },
                enabled = sourceUri != null && pageCount > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ESignatureAccent,
                    disabledContainerColor = Color(0xFF51414A)),
                shape = RoundedCornerShape(14.dp)) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text("Add Signature and Save PDF", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun eSignatureFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = ESignatureAccent,
    unfocusedBorderColor = Color(0xFF526075),
    focusedLabelColor = ESignatureAccent,
    unfocusedLabelColor = ESignatureMuted,
    cursorColor = ESignatureAccent
)

private fun getESignatureDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

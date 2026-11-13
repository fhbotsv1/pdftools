package com.littlebird.pdftoolsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val InsertPageBackground = Color(0xFF101522)
private val InsertPageSurface = Color(0xFF1B2433)
private val InsertPageAccent = Color(0xFFEF6262)
private val InsertPageMuted = Color(0xFF9AA9BD)

private data class InsertImage(val uri: Uri, val name: String)

@Composable
fun InsertPagesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var targetUri by remember { mutableStateOf<Uri?>(null) }
    var targetName by remember { mutableStateOf<String?>(null) }
    var targetPageCount by remember { mutableIntStateOf(0) }
    var insertAfter by remember { mutableStateOf("0") }
    var insertMode by remember { mutableStateOf("Blank pages") }
    var blankCount by remember { mutableStateOf("1") }
    var sourcePdfUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePdfName by remember { mutableStateOf<String?>(null) }
    var sourcePdfPageCount by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf<List<InsertImage>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Reading target PDF…"
                try {
                    val count = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            PDDocument.load(input).use { it.numberOfPages }
                        } ?: error("Could not open the selected PDF.")
                    }
                    require(count > 0) { "The selected PDF has no pages." }
                    targetUri = uri
                    targetName = insertPagesDisplayName(context, uri)
                    targetPageCount = count
                    insertAfter = count.toString()
                    status = "Target PDF loaded. Choose what to insert and where."
                } catch (error: Exception) {
                    targetUri = null
                    targetName = null
                    targetPageCount = 0
                    status = "Could not read PDF: ${error.localizedMessage ?: "Check that it is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val sourcePdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                status = "Reading source PDF…"
                try {
                    val count = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            PDDocument.load(input).use { it.numberOfPages }
                        } ?: error("Could not open the source PDF.")
                    }
                    require(count > 0) { "The source PDF has no pages." }
                    sourcePdfUri = uri
                    sourcePdfName = insertPagesDisplayName(context, uri)
                    sourcePdfPageCount = count
                    status = "Ready to insert all $count pages from the selected PDF."
                } catch (error: Exception) {
                    sourcePdfUri = null
                    sourcePdfName = null
                    sourcePdfPageCount = 0
                    status = "Could not read source PDF: ${error.localizedMessage ?: "Check that it is valid and unlocked."}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            images = uris.map { InsertImage(it, insertPagesDisplayName(context, it)) }
            status = "${images.size} image(s) ready to insert, one image per page."
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val target = targetUri
        val sourcePdf = sourcePdfUri
        val imageList = images.toList()
        val mode = insertMode
        val count = blankCount.toIntOrNull() ?: 0
        val after = insertAfter.toIntOrNull() ?: -1
        if (destination != null && target != null) {
            scope.launch {
                isWorking = true
                status = "Inserting pages…"
                try {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val targetInput = context.contentResolver.openInputStream(target)
                            ?: error("Could not open target PDF.")
                        val resultBytes = targetInput.use { targetStream ->
                            PDDocument.load(targetStream).use { targetDoc ->
                                when (mode) {
                                    "Blank pages" -> {
                                        require(count in 1..100) { "Choose between 1 and 100 blank pages." }
                                        repeat(count) { targetDoc.pages.insertAfter(PDPage(PDRectangle.A4), targetDoc.getPage(after.coerceAtMost(targetDoc.numberOfPages - 1))) }
                                        if (after == 0) {
                                            repeat(count) { targetDoc.pages.remove(targetDoc.numberOfPages - 1); targetDoc.pages.insertBefore(PDPage(PDRectangle.A4), targetDoc.getPage(0)) }
                                        }
                                    }
                                    "Another PDF" -> {
                                        val source = sourcePdf ?: error("Select the PDF whose pages you want to insert.")
                                        val sourceInput = context.contentResolver.openInputStream(source)
                                            ?: error("Could not open source PDF.")
                                        sourceInput.use { sourceStream ->
                                            PDDocument.load(sourceStream).use { sourceDoc ->
                                                val imported = (0 until sourceDoc.numberOfPages).map { sourceDoc.getPage(it) }
                                                if (after == 0) {
                                                    imported.forEach { targetDoc.importPage(it).also { page -> targetDoc.pages.remove(page); targetDoc.pages.insertBefore(page, targetDoc.getPage(0)) } }
                                                } else {
                                                    var anchor = targetDoc.getPage(after - 1)
                                                    imported.forEach { sourcePage ->
                                                        val newPage = targetDoc.importPage(sourcePage)
                                                        targetDoc.pages.insertAfter(newPage, anchor)
                                                        anchor = newPage
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        require(imageList.isNotEmpty()) { "Select at least one image." }
                                        val pageWidth = PDRectangle.A4.width
                                        val pageHeight = PDRectangle.A4.height
                                        var anchor = if (after > 0) targetDoc.getPage(after - 1) else null
                                        imageList.forEach { image ->
                                            val bitmap = decodeInsertImage(context, image.uri)
                                                ?: error("Could not decode image: ${image.name}")
                                            try {
                                                val page = PDPage(PDRectangle.A4)
                                                val bytes = ByteArrayOutputStream().use { output ->
                                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
                                                    output.toByteArray()
                                                }
                                                val imageObject = PDImageXObject.createFromByteArray(targetDoc, bytes, image.name)
                                                PDPageContentStream(targetDoc, page).use { stream ->
                                                    val scale = minOf(pageWidth / imageObject.width, pageHeight / imageObject.height)
                                                    val drawWidth = imageObject.width * scale
                                                    val drawHeight = imageObject.height * scale
                                                    stream.drawImage(imageObject, (pageWidth - drawWidth) / 2f, (pageHeight - drawHeight) / 2f, drawWidth, drawHeight)
                                                }
                                                if (anchor == null) {
                                                    targetDoc.pages.insertBefore(page, targetDoc.getPage(0))
                                                } else {
                                                    targetDoc.pages.insertAfter(page, anchor)
                                                }
                                                anchor = page
                                            } finally {
                                                bitmap.recycle()
                                            }
                                        }
                                    }
                                }
                                ByteArrayOutputStream().use { output ->
                                    targetDoc.save(output)
                                    output.toByteArray()
                                }
                            }
                        }
                        context.contentResolver.openOutputStream(destination)?.use { it.write(resultBytes) }
                            ?: error("Could not open the selected save location.")
                    }
                    status = "Updated PDF saved successfully. Original file is unchanged."
                } catch (error: Exception) {
                    status = "Could not insert pages: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                }
            }
        } else if (destination == null) {
            status = "Saving cancelled."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = InsertPageAccent, surface = InsertPageSurface)) {
        Column(modifier = modifier.fillMaxSize().background(InsertPageBackground).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text("Insert Pages", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Add blank pages, another PDF or images", color = InsertPageMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = InsertPageSurface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Target PDF", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { targetPicker.launch(arrayOf("application/pdf")) }, enabled = !isWorking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = InsertPageAccent)) {
                        Text(if (targetName == null) "Select PDF to modify" else "Choose another target PDF", color = Color.White)
                    }
                    if (targetName != null) {
                        Text(targetName!!, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$targetPageCount pages", color = InsertPageAccent, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("What do you want to insert?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Blank pages", "Another PDF", "Images").forEach { mode ->
                        FilterChip(
                            selected = insertMode == mode,
                            onClick = { insertMode = mode; status = null },
                            label = { Text(mode, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF54343D), selectedLabelColor = Color.White, labelColor = InsertPageMuted)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = InsertPageSurface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Insert position", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Enter the page number to insert after. Use 0 to insert at the beginning.", color = InsertPageMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = insertAfter,
                            onValueChange = { insertAfter = it.filter(Char::isDigit).take(5); status = null },
                            label = { Text("Insert after page (0–$targetPageCount)") },
                            enabled = targetPageCount > 0 && !isWorking,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = InsertPageAccent, unfocusedBorderColor = InsertPageMuted)
                        )
                        Spacer(Modifier.height(10.dp))
                        when (insertMode) {
                            "Blank pages" -> {
                                OutlinedTextField(
                                    value = blankCount,
                                    onValueChange = { blankCount = it.filter(Char::isDigit).take(3); status = null },
                                    label = { Text("Number of blank pages") },
                                    enabled = !isWorking,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = InsertPageAccent, unfocusedBorderColor = InsertPageMuted)
                                )
                            }
                            "Another PDF" -> {
                                Button(onClick = { sourcePdfPicker.launch(arrayOf("application/pdf")) }, enabled = !isWorking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = InsertPageAccent)) {
                                    Text(if (sourcePdfName == null) "Select source PDF" else "Choose another source PDF")
                                }
                                sourcePdfName?.let {
                                    Text(it, color = Color.White, fontSize = 12.sp)
                                    Text("$sourcePdfPageCount pages will be inserted", color = InsertPageMuted, fontSize = 11.sp)
                                }
                            }
                            else -> {
                                Button(onClick = { imagesPicker.launch(arrayOf("image/*")) }, enabled = !isWorking, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = InsertPageAccent)) {
                                    Text(if (images.isEmpty()) "Select images" else "Change selected images")
                                }
                                Text("${images.size} image(s) selected · one image per page", color = InsertPageMuted, fontSize = 11.sp)
                                images.forEachIndexed { index, image ->
                                    Text("${index + 1}. ${image.name}", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Pages are inserted into a new copy. Your original PDF is not modified.", color = InsertPageMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            status?.let {
                Text(it, color = InsertPageMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            Button(
                onClick = {
                    val after = insertAfter.toIntOrNull()
                    val validSource = when (insertMode) {
                        "Blank pages" -> (blankCount.toIntOrNull() ?: 0) in 1..100
                        "Another PDF" -> sourcePdfUri != null
                        else -> images.isNotEmpty()
                    }
                    if (after == null || after !in 0..targetPageCount) {
                        status = "Enter an insertion position from 0 to $targetPageCount."
                    } else if (!validSource) {
                        status = when (insertMode) {
                            "Blank pages" -> "Choose between 1 and 100 blank pages."
                            "Another PDF" -> "Select a source PDF."
                            else -> "Select at least one image."
                        }
                    } else {
                        val base = (targetName ?: "Document").substringBeforeLast('.', targetName ?: "Document")
                        saveLauncher.launch("${base}_pages_inserted.pdf")
                    }
                },
                enabled = targetUri != null && targetPageCount > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InsertPageAccent, disabledContainerColor = Color(0xFF51414A)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else Text("Insert Pages & Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun insertPagesDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
}

private fun decodeInsertImage(context: Context, uri: Uri): Bitmap? =
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

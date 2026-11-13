package com.littlebird.pdftoolsapp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class PdfOfficeExportType { WORD, EXCEL }

private val OfficeBackground = Color(0xFF101522)
private val OfficeSurface = Color(0xFF1B2433)
private val OfficeAccent = Color(0xFFEF6262)
private val OfficeMuted = Color(0xFF9AA9BD)

@Composable
fun PdfOfficeExportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    exportType: PdfOfficeExportType
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWord = exportType == PdfOfficeExportType.WORD
    val title = if (isWord) "PDF to Word" else "PDF to Excel"
    val extension = if (isWord) "docx" else "xlsx"
    val mimeType = if (isWord) {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    } else {
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var isWorking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { destination ->
        val bytes = pendingBytes
        if (destination == null) {
            status = "Save cancelled."
            pendingBytes = null
        } else if (bytes == null) {
            status = "There is no converted file to save."
        } else {
            scope.launch {
                isWorking = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: error("Could not open the selected save location.")
                        output.use { it.write(bytes) }
                    }
                    status = "Converted .$extension file saved successfully. The original PDF is unchanged."
                } catch (error: Exception) {
                    status = "Could not save file: ${error.localizedMessage ?: "Unknown error"}"
                } finally {
                    isWorking = false
                    pendingBytes = null
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = getOfficePdfDisplayName(context, uri)
            pageCount = 0
            status = "Reading PDF…"
            scope.launch {
                isWorking = true
                try {
                    pageCount = withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected PDF.")
                        input.use { stream ->
                            PDDocument.load(stream).use { it.numberOfPages }
                        }
                    }
                    status = "PDF loaded. Ready to convert all $pageCount page(s)."
                } catch (error: Exception) {
                    sourceUri = null
                    sourceName = null
                    status = "Could not read PDF: ${error.localizedMessage ?: "The file may be damaged or password-protected."}"
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

    fun convert() {
        val uri = sourceUri ?: return
        if (isWorking) return
        scope.launch {
            isWorking = true
            status = if (isWord) "Converting PDF text to Word…" else "Extracting rows for Excel…"
            try {
                val bytes = withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected PDF.")
                    input.use { stream ->
                        PDDocument.load(stream).use { document ->
                            if (document.isEncrypted) error("Encrypted PDFs are not supported. Remove the password first.")
                            val pageTexts = (1..document.numberOfPages).map { page ->
                                PDFTextStripper().apply {
                                    startPage = page
                                    endPage = page
                                    sortByPosition = true
                                }.getText(document).trimEnd()
                            }
                            if (pageTexts.all { it.isBlank() }) {
                                error("No selectable text was found. Scanned PDFs need OCR before conversion.")
                            }
                            if (isWord) createDocxBytes(pageTexts) else createXlsxBytes(pageTexts)
                        }
                    }
                }
                pendingBytes = bytes
                status = "Conversion complete. Choose where to save the .$extension file."
                val baseName = (sourceName ?: "Document").substringBeforeLast('.', sourceName ?: "Document")
                savePicker.launch("${baseName}_converted.$extension")
            } catch (error: Exception) {
                status = "Conversion failed: ${error.localizedMessage ?: "Unknown error"}"
            } finally {
                isWorking = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = OfficeAccent, surface = OfficeSurface)) {
        Column(
            modifier = modifier.fillMaxSize().background(OfficeBackground).padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 34.sp,
                    modifier = Modifier.padding(end = 14.dp).clickable { onBack() })
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isWord) "Create an editable Word document" else "Extract table-like data into a spreadsheet",
                        color = OfficeMuted, fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = OfficeSurface)
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Choose a PDF file", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { picker.launch(arrayOf("application/pdf")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = OfficeAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sourceName == null) "Select PDF file" else "Choose another PDF",
                            color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (sourceName != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(sourceName!!, color = Color.White, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("$pageCount page(s)", color = OfficeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B3021))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if (isWord) "Word conversion notes" else "Excel conversion notes",
                        color = Color(0xFFFFD18A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (isWord) {
                            "Creates an editable DOCX from selectable PDF text. Paragraphs and page breaks are retained where possible, but exact fonts, columns, images and complex layouts may not match. Scanned PDFs need OCR first."
                        } else {
                            "Creates an XLSX workbook by detecting text rows and separating columns where spacing or tabs indicate them. It works best with simple text-based tables. Complex layouts and scanned tables may need OCR or manual correction."
                        },
                        color = Color(0xFFE5D8C4), fontSize = 11.sp, lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(status!!, color = OfficeMuted, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = { convert() },
                enabled = sourceUri != null && pageCount > 0 && !isWorking,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OfficeAccent,
                    disabledContainerColor = Color(0xFF51414A)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(19.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Please wait…", color = Color.White, fontWeight = FontWeight.Bold)
                } else {
                    Text(if (isWord) "Convert to Word" else "Convert to Excel",
                        color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun createDocxBytes(pageTexts: List<String>): ByteArray {
    val paragraphs = buildString {
        pageTexts.forEachIndexed { pageIndex, pageText ->
            if (pageIndex > 0) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            pageText.split('\n').forEach { line ->
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(xmlEscape(line))
                append("</w:t></w:r></w:p>")
            }
        }
    }
    val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    return zipBytes(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""",
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""",
        "word/document.xml" to documentXml
    )
}

private fun createXlsxBytes(pageTexts: List<String>): ByteArray {
    val rows = mutableListOf<List<String>>()
    pageTexts.forEachIndexed { pageIndex, text ->
        if (pageIndex > 0) rows.add(listOf("Page ${pageIndex + 1}"))
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val cells = line.trim().split(Regex("\\t+| {2,}"))
                .map { it.trim() }.filter { it.isNotEmpty() }
            rows.add(if (cells.isEmpty()) listOf(line.trim()) else cells)
        }
    }
    if (rows.isEmpty()) error("No table rows could be extracted from this PDF.")
    val sheetRows = buildString {
        rows.forEachIndexed { rowIndex, cells ->
            append("<row r=\"${rowIndex + 1}\">")
            cells.forEachIndexed { colIndex, value ->
                val reference = "${excelColumnName(colIndex + 1)}${rowIndex + 1}"
                append("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(xmlEscape(value))
                append("</t></is></c>")
            }
            append("</row>")
        }
    }
    val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheetRows</sheetData></worksheet>"""
    return zipBytes(
        "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""",
        "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
        "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Extracted Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""",
        "xl/worksheets/sheet1.xml" to sheetXml
    )
}

private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun excelColumnName(column: Int): String {
    var number = column
    val result = StringBuilder()
    while (number > 0) {
        val remainder = (number - 1) % 26
        result.insert(0, ('A'.code + remainder).toChar())
        number = (number - 1) / 26
    }
    return result.toString()
}

private fun getOfficePdfDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF document"
}

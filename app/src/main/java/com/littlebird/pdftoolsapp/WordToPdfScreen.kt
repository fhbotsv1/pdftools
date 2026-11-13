package com.littlebird.pdftoolsapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

private val WordPdfBackground = Color(0xFF101522)
private val WordPdfSurface = Color(0xFF1B2433)
private val WordPdfAccent = Color(0xFFEF6262)
private val WordPdfMuted = Color(0xFF9AA9BD)

@Composable
fun WordToPdfScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedName by remember { mutableStateOf<String?>(null) }
    var previewHtml by remember { mutableStateOf<String?>(null) }
    var previewWebView by remember { mutableStateOf<WebView?>(null) }
    var isConverting by remember { mutableStateOf(false) }
    var isPreviewReady by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isConverting = true
            isPreviewReady = false
            previewHtml = null
            errorMessage = null
            selectedName = queryDisplayName(context, uri)
            scope.launch {
                try {
                    val html = withContext(Dispatchers.IO) { convertDocxToHtml(context, uri) }
                    previewHtml = html
                } catch (exception: Exception) {
                    errorMessage = exception.localizedMessage?.let { "Could not read this DOCX file: $it" }
                        ?: "Could not read this DOCX file. Please choose a valid .docx document."
                } finally {
                    isConverting = false
                }
            }
        }
    }

    fun saveAsPdf() {
        val activity = context.findWordActivity()
        val webView = previewWebView
        if (activity == null || webView == null || !isPreviewReady) {
            errorMessage = "Wait for the document preview to finish loading."
            return
        }
        try {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val title = (selectedName ?: "Word document").substringBeforeLast('.').take(60)
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
                .setMinMargins(PrintAttributes.Margins(1000, 1000, 1000, 1000))
                .build()
            val adapter = webView.createPrintDocumentAdapter(title.ifBlank { "Word document" })
            printManager.print(title.ifBlank { "Word document" }, adapter, attributes)
        } catch (exception: Exception) {
            errorMessage = "Could not open PDF printing: ${exception.localizedMessage ?: "unknown error"}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewWebView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            previewWebView = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(WordPdfBackground).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
            )
            Column(Modifier.weight(1f)) {
                Text("Word to PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Convert a Word document into PDF", color = WordPdfMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = WordPdfSurface)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Choose a Word document", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "DOCX files are processed on this device. Basic text formatting, headings, tables and embedded pictures are supported.",
                    color = WordPdfMuted, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        documentPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                    },
                    enabled = !isConverting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = WordPdfAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (selectedName == null) "Select Word document" else "Choose another document", color = Color.White, fontWeight = FontWeight.Bold)
                }
                if (selectedName != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        selectedName!!,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isConverting) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = WordPdfAccent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("Reading and preparing document…", color = WordPdfMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Document preview", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (isPreviewReady) Text("Ready", color = Color(0xFF8DD6A8), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            if (previewHtml == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Select a .docx file to see its preview here.",
                        color = Color(0xFF667085),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            settings.javaScriptEnabled = false
                            settings.loadsImagesAutomatically = true
                            settings.defaultTextEncodingName = "UTF-8"
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isPreviewReady = true
                                }
                            }
                            previewWebView = this
                            loadDataWithBaseURL("file:///android_asset/", previewHtml!!, "text/html", "UTF-8", null)
                        }
                    },
                    update = { view ->
                        if (view.tag != previewHtml) {
                            view.tag = previewHtml
                            isPreviewReady = false
                            view.loadDataWithBaseURL("file:///android_asset/", previewHtml!!, "text/html", "UTF-8", null)
                        }
                        previewWebView = view
                    }
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(errorMessage!!, color = Color(0xFFFFB4AB), fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { saveAsPdf() },
            enabled = isPreviewReady && !isConverting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = WordPdfAccent,
                disabledContainerColor = WordPdfSurface
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save as PDF", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Choose Save as PDF in the Android print screen to select where to save it. Legacy .doc files are not supported yet.",
            color = WordPdfMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) return it.getString(0) ?: "Word document.docx"
    }
    return "Word document.docx"
}

private fun convertDocxToHtml(context: Context, uri: Uri): String {
    val entries = HashMap<String, ByteArray>()
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (name == "word/document.xml" || name == "word/_rels/document.xml.rels" || name.startsWith("word/media/")) {
                        entries[name] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: throw IllegalArgumentException("The selected file could not be opened.")

    val documentBytes = entries["word/document.xml"]
        ?: throw IllegalArgumentException("This is not a valid DOCX document.")
    val document = parseWordXml(documentBytes)
    val body = document.getElementsByTagNameNS("*", "body").item(0)
        ?: throw IllegalArgumentException("The Word document has no readable body.")

    val relationships = HashMap<String, String>()
    entries["word/_rels/document.xml.rels"]?.let { relBytes ->
        val relDoc = parseWordXml(relBytes)
        val relNodes = relDoc.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until relNodes.length) {
            val element = relNodes.item(i) as? Element ?: continue
            val id = element.getAttribute("Id")
            val target = element.getAttribute("Target")
            if (id.isNotBlank() && target.isNotBlank()) relationships[id] = target
        }
    }

    val htmlBody = StringBuilder()
    val children = body.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType != Node.ELEMENT_NODE) continue
        when (node.wordLocalName()) {
            "p" -> htmlBody.append(renderWordParagraph(node, entries, relationships))
            "tbl" -> htmlBody.append(renderWordTable(node, entries, relationships))
        }
    }

    return """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <style>
        @page { size: A4; margin: 18mm 16mm; }
        * { box-sizing: border-box; }
        body { font-family: Arial, Helvetica, sans-serif; color: #222; font-size: 11pt; line-height: 1.42; overflow-wrap: anywhere; }
        p { margin: 0 0 8pt 0; min-height: 1em; }
        .Heading1 { font-size: 20pt; font-weight: bold; margin-top: 14pt; margin-bottom: 8pt; }
        .Heading2 { font-size: 16pt; font-weight: bold; margin-top: 12pt; margin-bottom: 7pt; }
        .Heading3 { font-size: 13pt; font-weight: bold; margin-top: 10pt; margin-bottom: 6pt; }
        table { border-collapse: collapse; width: 100%; margin: 8pt 0 12pt; table-layout: auto; }
        td, th { border: 1px solid #888; padding: 5pt; vertical-align: top; }
        img { max-width: 100%; height: auto; }
        </style></head><body>$htmlBody</body></html>
    """.trimIndent()
}

private fun parseWordXml(bytes: ByteArray): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
    try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
    try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
}

private fun Node.wordLocalName(): String = localName ?: nodeName.substringAfter(':')

private fun childElements(node: Node): List<Element> {
    val result = ArrayList<Element>()
    val children = node.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child.nodeType == Node.ELEMENT_NODE) result.add(child as Element)
    }
    return result
}

private fun Element.firstChildNamed(name: String): Element? =
    childElements(this).firstOrNull { it.wordLocalName() == name }

private fun Element.descendantText(name: String): String? {
    val nodes = getElementsByTagNameNS("*", name)
    return if (nodes.length > 0) nodes.item(0).textContent else null
}

private fun renderWordParagraph(
    paragraph: Node,
    entries: Map<String, ByteArray>,
    relationships: Map<String, String>
): String {
    val element = paragraph as Element
    val properties = element.firstChildNamed("pPr")
    val style = properties?.firstChildNamed("pStyle")?.getAttributeNS("*", "val")
        ?: properties?.firstChildNamed("pStyle")?.getAttribute("w:val").orEmpty()
    val alignment = properties?.firstChildNamed("jc")?.let {
        it.getAttribute("w:val").ifBlank { it.getAttribute("val") }
    }
    val alignStyle = when (alignment) {
        "center" -> "text-align:center;"
        "right" -> "text-align:right;"
        "both", "distribute" -> "text-align:justify;"
        else -> ""
    }
    val pageBreakBefore = properties?.firstChildNamed("pageBreakBefore") != null
    val content = StringBuilder()
    for (child in childElements(element)) {
        if (child.wordLocalName() != "pPr") {
            content.append(renderWordInline(child, entries, relationships))
        }
    }
    val className = when (style.lowercase()) {
        "heading1", "title" -> "Heading1"
        "heading2", "subtitle" -> "Heading2"
        "heading3" -> "Heading3"
        else -> ""
    }
    val classAttr = if (className.isNotBlank()) " class=\"$className\"" else ""
    val breakStyle = if (pageBreakBefore) "page-break-before:always;" else ""
    return "<p$classAttr style=\"$alignStyle$breakStyle\">$content</p>"
}

private fun renderWordInline(
    node: Node,
    entries: Map<String, ByteArray>,
    relationships: Map<String, String>
): String {
    if (node.nodeType != Node.ELEMENT_NODE) return ""
    val element = node as Element
    return when (element.wordLocalName()) {
        "r" -> renderWordRun(element, entries, relationships)
        "hyperlink", "sdt", "sdtContent", "smartTag", "customXml", "ins", "del" ->
            childElements(element).joinToString("") { renderWordInline(it, entries, relationships) }
        "drawing", "pict" -> renderWordImage(element, entries, relationships)
        else -> ""
    }
}

private fun renderWordRun(
    run: Element,
    entries: Map<String, ByteArray>,
    relationships: Map<String, String>
): String {
    val props = run.firstChildNamed("rPr")
    val styles = StringBuilder()
    if (props?.firstChildNamed("b") != null) styles.append("font-weight:bold;")
    if (props?.firstChildNamed("i") != null) styles.append("font-style:italic;")
    if (props?.firstChildNamed("u") != null) styles.append("text-decoration:underline;")
    if (props?.firstChildNamed("strike") != null) styles.append("text-decoration:line-through;")
    props?.firstChildNamed("color")?.let {
        val color = it.getAttribute("w:val").ifBlank { it.getAttribute("val") }
        if (color.matches(Regex("[0-9A-Fa-f]{6}"))) styles.append("color:#$color;")
    }
    props?.firstChildNamed("sz")?.let {
        val halfPoints = it.getAttribute("w:val").ifBlank { it.getAttribute("val") }.toIntOrNull()
        if (halfPoints != null && halfPoints in 8..144) styles.append("font-size:${halfPoints / 2.0}pt;")
    }

    val text = StringBuilder()
    val children = run.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child.nodeType != Node.ELEMENT_NODE) continue
        when (child.wordLocalName()) {
            "t", "delText" -> text.append(escapeHtml(child.textContent ?: ""))
            "tab" -> text.append("&emsp;")
            "br", "cr" -> text.append("<br>")
            "drawing", "pict" -> text.append(renderWordImage(child, entries, relationships))
        }
    }
    return if (styles.isNotEmpty()) "<span style=\"$styles\">$text</span>" else text.toString()
}

private fun renderWordImage(
    node: Node,
    entries: Map<String, ByteArray>,
    relationships: Map<String, String>
): String {
    val element = node as? Element ?: return ""
    val blips = element.getElementsByTagNameNS("*", "blip")
    if (blips.length == 0) return ""
    val blip = blips.item(0) as? Element ?: return ""
    val relationshipId = blip.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
        .ifBlank { blip.getAttribute("r:embed") }
    val target = relationships[relationshipId] ?: return ""
    val path = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("word/") -> target
        else -> "word/$target"
    }.replace("../", "")
    val bytes = entries[path] ?: return ""
    val extension = path.substringAfterLast('.', "png").lowercase()
    val mime = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }
    val encoded = Base64.getEncoder().encodeToString(bytes)
    return "<img src=\"data:$mime;base64,$encoded\" alt=\"Word document image\">"
}

private fun renderWordTable(
    table: Node,
    entries: Map<String, ByteArray>,
    relationships: Map<String, String>
): String {
    val html = StringBuilder("<table>")
    for (row in childElements(table).filter { it.wordLocalName() == "tr" }) {
        html.append("<tr>")
        for (cell in childElements(row).filter { it.wordLocalName() == "tc" }) {
            html.append("<td>")
            val cellChildren = childElements(cell)
            for (child in cellChildren) {
                when (child.wordLocalName()) {
                    "p" -> html.append(renderWordParagraph(child, entries, relationships))
                    "tbl" -> html.append(renderWordTable(child, entries, relationships))
                }
            }
            html.append("</td>")
        }
        html.append("</tr>")
    }
    html.append("</table>")
    return html.toString()
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private tailrec fun Context.findWordActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findWordActivity()
    else -> null
}

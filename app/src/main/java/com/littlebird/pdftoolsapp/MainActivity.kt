package com.littlebird.pdftoolsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppBackground = Color(0xFF101522)
private val AppSurface = Color(0xFF1B2433)
private val AppAccent = Color(0xFFEF6262)
private val MutedText = Color(0xFF9AA9BD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PdfToolsApp() }
    }
}

private data class PdfTool(
    val name: String,
    val description: String,
    val symbol: String,
    val category: String
)

private val pdfTools = listOf(
    PdfTool("Images to PDF", "Combine photos and images into a PDF", "▧", "Create"),
    PdfTool("Web to PDF", "Save a web page as a PDF", "◎", "Create"),
    PdfTool("Word to PDF", "Convert Word documents into PDF", "W", "Create"),
    PdfTool("Merge PDF", "Combine multiple PDF files", "⊕", "Edit"),
    PdfTool("Delete Pages", "Remove selected pages from a PDF", "✕", "Edit"),
    PdfTool("Insert Pages", "Add blank pages, PDFs or images", "＋", "Edit"),
    PdfTool("Highlight & Markup", "Highlight, draw and add notes to PDF pages", "✎", "Edit"),
    PdfTool("Fill Forms", "Complete interactive PDF form fields", "☑", "Edit"),
    PdfTool("Metadata Editor", "Edit or remove PDF document information", "ⓘ", "Secure"),
    PdfTool("PDF to Word", "Convert PDF text into an editable DOCX file", "W", "Convert"),
    PdfTool("PDF to Excel", "Extract PDF rows and tables into XLSX", "▦", "Convert"),
    PdfTool("Permanent Redaction", "Permanently remove selected page areas", "▰", "Secure"),
    PdfTool("Split PDF", "Separate a PDF into smaller files", "⇱", "Edit"),
    PdfTool("Reorder / Rotate", "Change page order or orientation", "↻", "Edit"),
    PdfTool("Cut Page", "Crop page margins and visible area", "⌗", "Edit"),
    PdfTool("Compress PDF", "Reduce PDF file size", "⇣", "Edit"),
    PdfTool("To Image / Extract", "Convert PDF pages or extract embedded images", "▣", "Edit"),
    PdfTool("PDF to Text", "Extract selectable text from a PDF", "≡", "Convert"),
    PdfTool("OCR PDF", "Recognise text in scanned PDF pages", "⌕", "Convert"),
    PdfTool("Annotate PDF", "Add headers, footers, watermarks and page numbers", "◇", "Edit"),
    PdfTool("Add Password", "Protect a PDF with a password", "🔒", "Secure"),
    PdfTool("Remove Password", "Remove protection from an accessible PDF", "🔓", "Secure"),
    PdfTool("E-Signature", "Add a drawn, typed or imported signature to a PDF", "✍", "Secure")
)

private val sections = listOf(
    "Favorites" to "★",
    "Create" to "＋",
    "Edit" to "✎",
    "Secure" to "⬡"
)

@Composable
private fun PdfToolsApp() {
    var selectedSection by remember { mutableStateOf("Favorites") }
    var selectedTool by remember { mutableStateOf<PdfTool?>(null) }
    val favorites = remember { mutableStateListOf<String>() }

    BackHandler(enabled = selectedTool != null) { selectedTool = null }

    val visibleTools = if (selectedSection == "Favorites") {
        pdfTools.filter { it.name in favorites }
    } else {
        pdfTools.filter { it.category == selectedSection }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppAccent,
            secondary = Color(0xFFFFB4AB),
            background = AppBackground,
            surface = AppSurface
        )
    ) {
        Scaffold(
            containerColor = AppBackground,
            bottomBar = {
                NavigationBar(containerColor = AppSurface) {
                    sections.forEach { (name, symbol) ->
                        NavigationBarItem(
                            selected = selectedSection == name && selectedTool == null,
                            onClick = {
                                selectedSection = name
                                selectedTool = null
                            },
                            icon = { Text(symbol, fontSize = 21.sp) },
                            label = { Text(name, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppAccent,
                                selectedTextColor = AppAccent,
                                indicatorColor = Color(0xFF3A2933),
                                unselectedIconColor = MutedText,
                                unselectedTextColor = MutedText
                            )
                        )
                    }
                }
            }
        ) { padding ->
            if (selectedTool == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(AppBackground)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("PDF Tools", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Everything you need for PDF files", fontSize = 13.sp, color = MutedText)
                            }
                            Box(
                                modifier = Modifier.size(50.dp).background(Color(0xFF3A2933), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("PDF", color = AppAccent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF392630))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("23 PDF UTILITIES", color = Color(0xFFFFB4AB), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("Create, edit and protect your PDFs.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                }
                                Text("▤", color = AppAccent, fontSize = 48.sp)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        Text(
                            if (selectedSection == "Favorites") "Your Favorites" else "$selectedSection tools",
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (selectedSection) {
                                "Favorites" -> "Keep the PDF tools you use most close at hand"
                                "Create" -> "Create PDF files from different sources"
                                "Edit" -> "Organize, adjust and annotate PDF pages"
                                "Convert" -> "Convert PDFs into editable and reusable formats"
                                else -> "Protect, sign and sanitize PDF documents"
                            },
                            color = MutedText, fontSize = 12.sp
                        )
                    }
                    if (visibleTools.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("☆", color = AppAccent, fontSize = 54.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("No favorites yet", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Open a category and tap the star on a tool to add it here.", color = MutedText, fontSize = 13.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(visibleTools, key = { it.name }) { tool ->
                                PdfToolCard(
                                    tool = tool,
                                    favorite = tool.name in favorites,
                                    onFavorite = {
                                        if (tool.name in favorites) favorites.remove(tool.name) else favorites.add(tool.name)
                                    },
                                    onClick = { selectedTool = tool }
                                )
                            }
                        }
                    }
                }
            } else if (selectedTool!!.name == "Images to PDF") {
                ImagesToPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Web to PDF") {
                WebToPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Word to PDF") {
                WordToPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Insert Pages") {
                InsertPagesScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Highlight & Markup") {
                HighlightMarkupScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Fill Forms") {
                FillFormsScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Metadata Editor") {
                MetadataEditorScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "PDF to Word") {
                PdfOfficeExportScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding), exportType = PdfOfficeExportType.WORD)
            } else if (selectedTool!!.name == "PDF to Excel") {
                PdfOfficeExportScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding), exportType = PdfOfficeExportType.EXCEL)
            } else if (selectedTool!!.name == "Permanent Redaction") {
                PermanentRedactionScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Delete Pages") {
                DeletePagesScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Merge PDF") {
                MergePdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Split PDF") {
                SplitPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Reorder / Rotate") {
                ReorderRotatePdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Cut Page") {
                CutPageScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Compress PDF") {
                CompressPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "PDF to Image / Extract") {
                PdfImageExtractScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "PDF to Text") {
                PdfToTextScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "OCR PDF") {
                OcrPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Annotate PDF") {
                AnnotatePdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Add Password") {
                AddPasswordPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "Remove Password") {
                RemovePasswordPdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else if (selectedTool!!.name == "E-Signature") {
                ESignaturePdfScreen(onBack = { selectedTool = null }, modifier = Modifier.padding(padding))
            } else {
                ToolComingSoonScreen(
                    tool = selectedTool!!,
                    isFavorite = selectedTool!!.name in favorites,
                    onBack = { selectedTool = null },
                    onFavorite = {
                        val name = selectedTool!!.name
                        if (name in favorites) favorites.remove(name) else favorites.add(name)
                    }
                )
            }
        }
    }
}

@Composable
private fun PdfToolCard(
    tool: PdfTool,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(150.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(Color(0xFF3A2933), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tool.symbol, color = AppAccent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (favorite) "★" else "☆",
                    color = if (favorite) Color(0xFFFFD166) else MutedText,
                    fontSize = 25.sp,
                    modifier = Modifier.clickable(onClick = onFavorite).padding(start = 8.dp)
                )
            }
            Column {
                Text(tool.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(tool.description, color = MutedText, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ToolComingSoonScreen(
    tool: PdfTool,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onFavorite: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(AppBackground).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.name, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(tool.category, color = MutedText, fontSize = 12.sp)
            }
            Text(
                if (isFavorite) "★" else "☆",
                color = if (isFavorite) Color(0xFFFFD166) else MutedText,
                fontSize = 28.sp,
                modifier = Modifier.clickable(onClick = onFavorite).padding(6.dp)
            )
        }
        Spacer(Modifier.height(54.dp))
        Box(
            Modifier.size(104.dp).background(Color(0xFF3A2933), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(tool.symbol, color = AppAccent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(tool.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(tool.description, color = MutedText, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AppSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Coming in the next development stage", color = AppAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The interface is ready. We will implement this PDF operation and test it with real documents in its own stage.",
                    color = MutedText, fontSize = 13.sp, lineHeight = 19.sp
                )
            }
        }
    }
}

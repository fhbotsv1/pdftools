package com.littlebird.pdftoolsapp

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout

private val WebPdfBackground = Color(0xFF101522)
private val WebPdfSurface = Color(0xFF1B2433)
private val WebPdfAccent = Color(0xFFEF6262)
private val WebPdfMuted = Color(0xFF9AA9BD)

@Composable
fun WebToPdfScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var url by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var pageLoaded by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("Webpage preview") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadPage() {
        val entered = url.trim()
        if (entered.isBlank()) {
            errorMessage = "Enter a webpage address first."
            return
        }
        val normalized = when {
            entered.startsWith("https://", ignoreCase = true) ||
                entered.startsWith("http://", ignoreCase = true) -> entered
            else -> "https://$entered"
        }
        val parsed = android.net.Uri.parse(normalized)
        if (parsed.host.isNullOrBlank() || parsed.scheme !in listOf("http", "https")) {
            errorMessage = "Please enter a valid website address."
            return
        }
        url = normalized
        errorMessage = null
        pageLoaded = false
        focusManager.clearFocus()
        keyboard?.hide()
        webView?.loadUrl(normalized)
        if (webView == null) errorMessage = "The webpage preview is still starting. Please try again."
    }

    fun saveAsPdf() {
        val activity = context.findActivity()
        val currentWebView = webView
        if (activity == null || currentWebView == null || !pageLoaded) {
            errorMessage = "Load a webpage before creating its PDF."
            return
        }
        try {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = currentWebView.createPrintDocumentAdapter(
                pageTitle.ifBlank { "Webpage" }.take(60)
            )
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "PDF", 300, 300))
                .setMinMargins(PrintAttributes.Margins(1000, 1000, 1000, 1000))
                .build()
            printManager.print(
                pageTitle.ifBlank { "Webpage" }.take(60),
                adapter,
                attributes
            )
        } catch (exception: Exception) {
            errorMessage = "Could not open PDF printing: ${exception.localizedMessage ?: "unknown error"}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(WebPdfBackground).padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.padding(end = 14.dp).clickable { onBack() }
            )
            Column(Modifier.weight(1f)) {
                Text("Web to PDF", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Convert a webpage into a PDF", color = WebPdfMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                errorMessage = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Website URL") },
            placeholder = { Text("https://example.com") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { loadPage() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = WebPdfAccent,
                unfocusedBorderColor = WebPdfMuted,
                focusedLabelColor = WebPdfAccent,
                unfocusedLabelColor = WebPdfMuted,
                cursorColor = WebPdfAccent
            )
        )

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = { loadPage() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = WebPdfAccent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Load webpage", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isLoading) "Loading webpage…" else if (pageLoaded) pageTitle else "Webpage preview",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                Text("$progress%", color = WebPdfMuted, fontSize = 12.sp)
            }
        }

        if (isLoading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = WebPdfAccent,
                trackColor = WebPdfSurface
            )
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(AndroidColor.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                                isLoading = true
                                pageLoaded = false
                                progress = 0
                                errorMessage = null
                            }

                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                isLoading = false
                                pageLoaded = true
                                pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: "Webpage"
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    pageLoaded = false
                                    errorMessage = "Could not load this webpage. Check the address and internet connection."
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        webView = this
                    }
                }
            )
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(errorMessage!!, color = Color(0xFFFFB4AB), fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { saveAsPdf() },
            enabled = pageLoaded && !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = WebPdfAccent,
                disabledContainerColor = WebPdfSurface
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save as PDF", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "PDF settings and Save as PDF are available in the Android print screen.",
            color = WebPdfMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

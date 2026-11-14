package dev.jarvis.app

import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Displays Google's accompanying search widget without script or local-file access. */
@Composable
fun SearchSuggestions(html: String, onLink: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        factory = { context -> WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.hasGesture() && GeminiConnector.safeWebUrl(request.url.toString())) {
                        onLink(request.url.toString())
                    }
                    return true
                }
            }
        } },
        onReset = null,
        onRelease = { it.destroy() },
        update = { view ->
            if (view.tag != html) { view.tag = html; view.loadDataWithBaseURL("https://www.google.com/", html, "text/html", "UTF-8", null) }
        },
    )
}

package app.lenews.item.view

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.text.SpannedString
import android.util.AttributeSet
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.text.HtmlCompat
import androidx.core.text.layoutDirection
import app.lenews.R
import app.lenews.util.Utils
import app.lenews.db.pojo.ItemWithFeed
import org.jsoup.parser.Parser
import java.util.Locale

@SuppressLint("ViewConstructor")
class ItemWebView(
    context: Context,
    onUrlClick: (String) -> Unit,
    onImageLongPress: (String) -> Unit,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    init {
        applyReaderSettings(settings)

        isVerticalScrollBarEnabled = false

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()

                // Only a web page leaves the article, and it leaves through the
                // app's own way of opening a link. Anything else — file:,
                // content:, intent:, javascript:, tel:, a scheme some other app
                // registered — is dropped here rather than handed to
                // startActivity, which is what crashes upstream with
                // FileUriExposedException. True either way: the WebView itself
                // navigates nowhere.
                if (ArticleLinks.mayOpen(url)) {
                    onUrlClick(url)
                }

                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            // target="_blank" and window.open. The safelist drops the target
            // attribute and JavaScript is off, so neither can happen; refusing
            // the window says what happens if one ever does — the link is not
            // opened in a second WebView nobody configured.
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean = false
        }

        setOnLongClickListener {
            val type = hitTestResult.type
            if (type == HitTestResult.IMAGE_TYPE || type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { onImageLongPress(it) }
            }

            false
        }
    }

    fun loadText(
        itemWithFeed: ItemWithFeed,
        accentColor: Color,
        backgroundColor: Color,
        onBackgroundColor: Color
    ) {
        val direction = if (Locale.getDefault().layoutDirection == LAYOUT_DIRECTION_LTR) {
            "ltr"
        } else {
            "rtl"
        }

        val string = context.getString(
            R.string.webview_html_template,
            Utils.getCssColor(accentColor.toArgb()),
            Utils.getCssColor(onBackgroundColor.toArgb()),
            Utils.getCssColor(backgroundColor.toArgb()),
            direction,
            formatText(itemWithFeed)
        )

        // The base URL is what the stylesheet's `url("fonts/Inter-Regular.woff2")`
        // resolves against: the Inter font ships in the APK's assets. Assets are
        // reachable from this base whatever allowFileAccess says — that setting
        // governs the rest of the file system, not android_asset — so the font
        // still arrives with file access off. Nothing in the article can point
        // at the assets itself: every URL in it was resolved and checked before
        // it got here, and a relative one that could not be resolved was dropped.
        loadDataWithBaseURL(
            "file:///android_asset/",
            string,
            "text/html; charset=utf-8",
            "UTF-8",
            null
        )
    }

    private fun formatText(itemWithFeed: ItemWithFeed): String {
        val text = itemWithFeed.item.text ?: return ""
        val unescapedText = Parser.unescapeEntities(text, false)

        // Relative URLs resolve against the article's own address, never against
        // the base URL the page is loaded with. The feed's site is the fallback
        // for an article that carries no link of its own.
        val articleUrl = itemWithFeed.item.link ?: itemWithFeed.websiteUrl

        // A feed is allowed to put plain text in a field that says HTML, and
        // several do. Turn its line breaks into paragraphs rather than render one
        // long line — then sanitise that too, so there is one way into the page
        // rather than two.
        val html = if (ArticleHtml.looksLikePlainText(unescapedText)) {
            HtmlCompat.toHtml(
                SpannedString(unescapedText),
                HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE
            )
        } else {
            unescapedText
        }

        return ArticleHtml.sanitise(html, articleUrl)
    }

    companion object {

        /**
         * What the article WebView is allowed to do. An article is someone
         * else's HTML, so the answer is: display it, and nothing more.
         *
         * JavaScript is off, which is what makes every remaining hole in a feed
         * harmless — an `onerror` the sanitiser somehow missed, a
         * `data:image/svg+xml` with a script element in it, a stylesheet import.
         * File and content access are off, so nothing in the page can read the
         * app's own files or reach a content provider. DOM storage is off
         * because with no script there is nothing to write it, and a store the
         * reader cannot see is a store nobody clears. Multiple windows are off,
         * so a link has one place to go and it is the rule in
         * [shouldOverrideUrlLoading][WebViewClient.shouldOverrideUrlLoading].
         *
         * Assets are the exception on purpose and not a hole: the Inter font is
         * served to the page from `file:///android_asset/`, which
         * `allowFileAccess` does not govern.
         *
         * It is a function of its own so the whole answer reads in one place,
         * next to the reason for each line, rather than scattered through the
         * constructor.
         */
        fun applyReaderSettings(settings: WebSettings) {
            settings.javaScriptEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = false
            settings.setSupportMultipleWindows(false)

            // Pinch to zoom on an article, without the zoom buttons over it.
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
        }
    }
}

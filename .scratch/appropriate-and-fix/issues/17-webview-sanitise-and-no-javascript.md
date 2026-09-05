# 17 — Stop executing feed HTML as JavaScript

Type: task
Status: open
Blocked by: 03

## Question

Review finding (high), `app/.../item/view/ItemWebView.kt:28-99`. Article content from FreshRSS is parsed with Jsoup and loaded into a WebView with JavaScript enabled and a `file:///android_asset/` base URL. Parsing is not sanitising: only `div`/`span` attributes are cleared, so `script`, event handlers, `javascript:` URLs, iframes and embedded content survive. A malicious feed runs script in the reader with file-origin privileges.

Disable JavaScript for article rendering. Sanitise with an explicit Jsoup `Safelist` (start from `Safelist.relaxed()`, keep `img`, `a`, `pre`, `code`, `blockquote`, tables; strip active and embedded elements; allow only `http`/`https`/`data:image` schemes). Disable file and content access on the WebView; allow navigation only to validated `http`/`https` URLs, everything else goes to the system. Check what the asset base URL is for (fonts, CSS) and keep that working without JavaScript — the Inter fonts are loaded from assets.

Also from upstream's tracker (issues #353, #354, #355, #357, #358, all open): tapping certain links inside the article WebView crashes with `FileUriExposedException`, because the URL is handed to `startActivity` without a scheme check. The navigation rule above (only validated `http`/`https` leaves the WebView, everything else is dropped or handed to the system with a try/catch) closes it; add a `file:` link to the corpus.

Test-first (`/tdd`): a JVM test corpus with scripts, `onload`/`onclick`, an iframe, an `<object>`, a `javascript:` link, a `data:text/html` link, and a benign article with an image and a code block; assert what survives.

**Done when** the gate is green, the corpus tests pass, `settings.javaScriptEnabled` is false, and a real article with an image still renders in the debug build.

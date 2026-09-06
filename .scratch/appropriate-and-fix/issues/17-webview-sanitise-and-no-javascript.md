# 17 — Stop executing feed HTML as JavaScript

Type: task
Status: resolved
Blocked by: 03

## Question

Review finding (high), `app/.../item/view/ItemWebView.kt:28-99`. Article content from FreshRSS is parsed with Jsoup and loaded into a WebView with JavaScript enabled and a `file:///android_asset/` base URL. Parsing is not sanitising: only `div`/`span` attributes are cleared, so `script`, event handlers, `javascript:` URLs, iframes and embedded content survive. A malicious feed runs script in the reader with file-origin privileges.

Disable JavaScript for article rendering. Sanitise with an explicit Jsoup `Safelist` (start from `Safelist.relaxed()`, keep `img`, `a`, `pre`, `code`, `blockquote`, tables; strip active and embedded elements; allow only `http`/`https`/`data:image` schemes). Disable file and content access on the WebView; allow navigation only to validated `http`/`https` URLs, everything else goes to the system. Check what the asset base URL is for (fonts, CSS) and keep that working without JavaScript — the Inter fonts are loaded from assets.

Also from upstream's tracker (issues #353, #354, #355, #357, #358, all open): tapping certain links inside the article WebView crashes with `FileUriExposedException`, because the URL is handed to `startActivity` without a scheme check. The navigation rule above (only validated `http`/`https` leaves the WebView, everything else is dropped or handed to the system with a try/catch) closes it; add a `file:` link to the corpus.

Test-first (`/tdd`): a JVM test corpus with scripts, `onload`/`onclick`, an iframe, an `<object>`, a `javascript:` link, a `data:text/html` link, and a benign article with an image and a code block; assert what survives.

**Done when** the gate is green, the corpus tests pass, `settings.javaScriptEnabled` is false, and a real article with an image still renders in the debug build.

## Answer (2026-09-06)

Done. Review finding (high),
~~`app/.../item/view/ItemWebView.kt:28-99`~~ — the file was rewritten, the line
range no longer exists, and nothing in an article is executed any more.

### The sanitiser

`app/src/main/java/app/lenews/item/view/ArticleHtml.kt`, a plain Kotlin object
with no Android in it, so the whole corpus is a JVM unit test.

`sanitise(html, articleUrl)` cleans with an explicit jsoup `Safelist` built from
`Safelist.relaxed()`, which already carries headings, lists, `a`, `img`, `pre`,
`code`, `blockquote` and tables. On top of it: `figure`, `figcaption`, `hr`,
`s`, `del`, `ins` and `mark` are added; `ftp` and `mailto` are removed from a
link's protocols, leaving `http` and `https`; `data` is added to an image's
source. Nothing else is named, so `script`, `style`, `iframe`, `object`,
`embed`, `form`, `svg`, `math`, `base`, `meta` and every attribute the safelist
does not list — every `on*` handler included — are gone by construction rather
than by a list of things to remove.

Three decisions the ticket asked to be stated:

- **`video` and `audio` are stripped.** They are not in the relaxed safelist and
  nothing adds them. A player in the reader would fetch media from a third party
  on its own; the article's own site is one tap away in the bottom bar.
- **`srcset` and `sizes` are removed**, for the same reason they are not in the
  relaxed safelist: they are a second list of image URLs, and the point of the
  file is that every URL rendered has been through one check. The image loads
  from its `src`, which has — and when the feed put the source in `srcset`
  alone, the first candidate becomes that `src` before the clean, so it goes
  through the same check rather than the image disappearing (review round
  below).
- **A `data:image/svg+xml` image that carries script is inert.** An image
  document runs no script in any engine, and the WebView has JavaScript off
  besides.

URLs: every one is resolved to an absolute URL against **the article's own link**
(`Item.link`, falling back to the feed's site URL) before it is checked, and
jsoup writes the absolute form back into the attribute. `http` and `https` on a
link; `http`, `https` and `data:image/` on an image source. jsoup can only say
"data:", so `data:text/html` in an `img src` is caught by a second pass after
the clean, which also drops an image left with no source at all. **No relative
URL can resolve against the asset base**: with no article link there is no base
URI, so a relative source cannot be made absolute and the image is dropped
rather than left pointing into `file:///android_asset/`.

One behaviour of jsoup's cleaner is asserted rather than assumed: an unsafe tag
is *unwrapped*, not cut out. `<script>` and `<style>` leave nothing, because
what they hold is data; `<object>` leaves its fallback sentence and `<button>`
its label, as escaped text. That is content, not code, and the tests name it.

### The navigation rule

`app/src/main/java/app/lenews/item/view/ArticleLinks.kt`: `mayOpen(url)` is true
only for `http`/`https` with something after the scheme, read without regard to
case, surrounding whitespace ignored, and any control character anywhere is a
refusal — a tab inside `java<tab>script:` is how a scheme gets smuggled past a
check that only reads the start. It is one function because two places need the
same answer: the sanitiser deciding which `href` survives, and the WebView
deciding what to hand to `startActivity`.

`ItemWebView` overrides the `WebResourceRequest` form of
`shouldOverrideUrlLoading` and returns `true` in every case, so the WebView
itself navigates nowhere: a URL that passes the rule goes to the app's existing
`onOpenUrl` (in-app custom tab, or the system browser when the preference says
so), and `file:`, `content:`, `intent:`, `javascript:`, `tel:`, `mailto:` and
any custom scheme are dropped. `Context.openUrl` and `Context.openInCustomTab`
now catch `ActivityNotFoundException` — the custom tab falls back to `openUrl`,
and `openUrl` logs and does nothing — so a phone with nothing to open a link
with no longer takes the app down.

That closed the WebView's own links. It did **not** close upstream #353 to #358
everywhere, and this section first claimed it did: the rule sat at one caller
rather than at the two helpers every caller ends in, so the article toolbar's
"open in browser" button still handed `Item.link` over untouched. The review
round below is what makes "nothing but a web URL is ever handed to
`startActivity`" actually true. `target="_blank"` loses its attribute in the sanitiser,
`setSupportMultipleWindows(false)` is explicit, and `onCreateWindow` returns
`false`, so there is no second WebView nobody configured.

### The WebView settings

`ItemWebView.applyReaderSettings` sets, in one place with the reason beside each:
`javaScriptEnabled = false`, `javaScriptCanOpenWindowsAutomatically = false`,
`allowFileAccess = false`, `allowContentAccess = false`,
`allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`,
`domStorageEnabled = false`, `setSupportMultipleWindows(false)`. The
`@SuppressLint("SetJavaScriptEnabled")` on the class is gone with the reason for
it.

### The asset base URL, verified rather than assumed

The `file:///android_asset/` base URL exists for one thing: the stylesheet's
`url("fonts/Inter-Regular.woff2")`, the Inter font that ships in the APK's
assets. It **still works with `allowFileAccess = false`** — that setting governs
the rest of the file system, not `android_asset` — so `loadDataWithBaseURL` was
kept and **`WebViewAssetLoader` was not needed**, which also means no new
dependency.

It was verified rather than read off the documentation: the same article was
rendered twice on the emulator, once as shipped and once from a build whose CSS
pointed at a font file that does not exist. The two screenshots differ — eight
lines of body text against seven, different letterforms — so the WebView really
did fetch the font from the assets with file access off. `html.xml` was put back
with `git checkout` and the correct APK reinstalled.

### Tests

- `app/src/test/java/app/lenews/item/view/ArticleHtmlTest.kt` — 24 tests, each
  asserting the **exact** surviving markup: `<script>`, `<style>`, `onclick` and
  `onload`, `<iframe>`, `<embed>`, `<object>`, `<svg onload>`, `<math>`,
  `<form>` with an input and a button, `<base>` and `<meta http-equiv=refresh>`,
  `<video>`/`<audio>`, a `javascript:` link plain, mixed case, with a tab and
  entity-encoded, a `data:text/html` link, a `file:` link, `content:`, `intent:`,
  `tel:`, `<a target=_blank>`, a relative link, a relative image, a
  protocol-relative image with and without an article URL, a `data:image/png`
  image, a `data:text/html` image, `srcset` (four cases more since the review
  round), and a benign article with an image,
  a figure with a caption, a code block, a table, a list and a blockquote that
  comes through byte for byte unchanged.
- `app/src/test/java/app/lenews/item/view/ArticleLinksTest.kt` — 8 tests on the
  open/drop rule for every scheme above.
- `app/src/androidTest/java/app/lenews/item/view/ItemWebViewSettingsTest.kt` —
  builds a real `ItemWebView` on the main thread and asserts all eight settings,
  so a default cannot drift back.

### On the emulator

`bench-pixel6-aosp` on `emulator-5554`, the debug build installed over the
`ledev` store already there. A real article with an image (Caradisiac, "Tuerie
de Chevaline") opens: the image is visible, the body is set in Inter, no blank
page, no crash. Screenshot at `/tmp/lenews-run2/ticket-17-article.png`, and the
missing-font comparison at `/tmp/lenews-run2/ticket-17-font-missing.png`.
`local.properties` was **not** copied into the worktree: the debug store on the
emulator is already logged in, and those three keys only autofill the login
screen.

### Left out on purpose

- **The image dialog still takes whatever `hitTestResult.extra` gives it**,
  including a `data:image/...` URL, and hands it to Coil to share or download.
  That is a sanitised source, so it is not a hole; making the dialog sensible
  about inline images is its own small ticket.
- **The stylesheet still styles `iframe` and `video`**, tags that can no longer
  reach the page. Harmless dead CSS, left rather than touched in a security
  change.
- **No test opens a tapped link end to end on the emulator.** The rule is unit
  tested and the WebView returns `true` unconditionally; watching a custom tab
  come up adds nothing the test does not already say.
- **`Item.imageLink`**, the picture behind the title, is not part of this and is
  not sanitised HTML — it is a URL column loaded by Coil.

### Review round (2026-09-06)

An adversarial review of the branch found two things, and both are fixed here.

**Every URL that leaves the app now goes through the rule, not just the
WebView's.** The rule was applied in `shouldOverrideUrlLoading`, so a link
tapped inside the article was checked — but the bottom bar's "open in browser"
button, the timeline's open-in-a-browser path and the More tab call
`Context.openUrl` / `Context.openInCustomTab` themselves, with whatever
`Item.link` holds. A feed that sets an article's link to `file:///sdcard/x.html`
therefore still reached `startActivity` and still threw
`FileUriExposedException`, which is the crash this ticket was meant to close.

`ArticleLinks.mayOpen` now sits **inside both helpers**, at the top: a URL that
is not an `http`/`https` address is logged — without the URL itself, which says
what is being read — and nothing happens. No caller can forget the check any
more, because there is nowhere left to forget it. Both helpers also catch
`FileUriExposedException` and `SecurityException` around `startActivity` as a
last line; they should be unreachable now, which is exactly why they cost
nothing. The custom tab still falls back to `openUrl` on
`ActivityNotFoundException` and on nothing else.

The toolbar button follows the same rule rather than a weaker one: it is shown
when `ArticleLinks.mayOpen(item.link)` instead of when the link is merely not
empty, so a link that would open nothing no longer offers a button, and the `!!`
on `item.link` is gone with it.

`app/src/androidTest/java/app/lenews/util/extensions/OpenUrlTest.kt` is the new
test: a `ContextWrapper` that records `startActivity` instead of launching it,
so what is asserted is which intents would have left the app. Four tests — a web
address goes through each helper as an `ACTION_VIEW` intent carrying that exact
address; `file:`, `content:`, `intent:`, `javascript:` plain and with a tab, a
custom scheme, `tel:`, `mailto:`, `data:text/html` and the empty string launch
nothing at all, through either helper. The decision function's own cases were
already covered by `ArticleLinksTest`.

**An image whose source is only in `srcset` survives.** The safelist strips
`srcset`, and the pass after the clean then removed the image for having no
`src` — so an ordinary picture, `<img srcset="…small.png 480w, …large.png
1200w" alt="Chart">`, vanished from the article. Every existing test had
supplied a fallback `src`, which hid it.

`ArticleHtml.sanitise` now hoists a `srcset` into `src` **before** the clean,
when and only when the image has no `src` of its own, so the candidate is
resolved against the article's link and checked by scheme exactly like any other
source. The **first** candidate is taken: a browser chooses by viewport and
pixel density, which is not a decision available while cleaning text, and the
first entry is the smallest in nearly every list a feed sends — the cheaper file
over mobile data. A candidate's URL ends at the first whitespace rather than at
the first comma, because a URL is allowed to contain a comma of its own. Four
exact-output tests: a `srcset`-only image with two https candidates keeps the
first and its `alt`; a relative candidate resolves against the article; a
candidate with a comma in it survives whole; and an image whose only candidate
is `javascript:`, or is relative with no article URL, is dropped exactly as
before.

Left out of this round: `TimelineTab.openItem` still writes
`itemWithFeed.item.link!!`, so a null link there would still throw — that is
upstream's own code and a different bug from the scheme check, which the helpers
now make safe whatever they are handed. The image dialog and the dead
`iframe`/`video` CSS listed above are still as they were.

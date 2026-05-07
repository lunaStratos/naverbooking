package com.lunastratos.naverbookingphone.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

@Serializable
data class BookingItem(val title: String, val url: String)

@Serializable
data class CalendarDay(val text: String, val available: Boolean)

@Serializable
data class MonthResult(val title: String, val days: List<CalendarDay>) {
    val availableDays: List<String> get() = days.filter { it.available && it.text.isNotBlank() }.map { it.text }
}

@Serializable
data class CheckResult(val item: BookingItem, val months: List<MonthResult>) {
    val totalAvailable: Int get() = months.sumOf { it.availableDays.size }
}

class NaverBookingScraper(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageFinishedCallback: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun init() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val wv = WebView(context.applicationContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/127.0.0.0 Safari/537.36"
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageFinishedCallback?.invoke()
                pageFinishedCallback = null
            }
        }
        webView = wv
    }

    fun destroyOnMain() {
        main.post {
            webView?.let {
                runCatching { it.stopLoading() }
                runCatching { it.destroy() }
            }
            webView = null
        }
    }

    private suspend fun loadUrl(url: String): Unit = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            pageFinishedCallback = {
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { pageFinishedCallback = null }
            webView!!.loadUrl(url)
        }
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView!!.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result ?: "null")
            }
        }
    }

    private suspend fun waitForSelector(selector: String, timeoutMs: Long = 15_000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val raw = eval("document.querySelectorAll(${jsLiteral(selector)}).length")
            val count = raw.trim().toIntOrNull() ?: 0
            if (count > 0) return true
            delay(300)
        }
        return false
    }

    suspend fun discoverItems(placeId: String): List<BookingItem> {
        loadUrl("https://pcmap.place.naver.com/place/$placeId/ticket")
        delay(2500)
        val js = """
            (function(){
              var anchors = document.querySelectorAll('a[href*="m.booking.naver.com"]');
              var seen = {};
              var items = [];
              for (var i=0;i<anchors.length;i++){
                var a = anchors[i];
                var href = a.getAttribute('href');
                if (!href || seen[href]) continue;
                seen[href] = 1;
                var title = '';
                var li = a.closest('li');
                if (li) {
                  var lines = (li.innerText||'').split('\n');
                  for (var k=0;k<lines.length;k++){
                    var t = lines[k].trim();
                    if (t) { title = t; break; }
                  }
                }
                if (!title) {
                  var seg = href.split('/items/')[1];
                  if (seg) title = seg.split('?')[0];
                }
                items.push({title:title, url:href});
              }
              return items;
            })()
        """.trimIndent()
        val raw = eval(js)
        if (raw == "null" || raw.isBlank()) return emptyList()
        return json.decodeFromString(raw)
    }

    suspend fun checkItem(item: BookingItem, months: Int): CheckResult {
        loadUrl(item.url)
        if (!waitForSelector(".calendar_date")) return CheckResult(item, emptyList())
        val results = mutableListOf<MonthResult>()
        for (m in 0 until months) {
            val js = """
                (function(){
                  var titleEl = document.querySelector('.calendar_title');
                  var title = titleEl ? (titleEl.innerText||'').trim() : '';
                  var cells = document.querySelectorAll('.calendar_date');
                  var bad = ['unselectable','dayoff','closed','today'];
                  var days = [];
                  for (var i=0;i<cells.length;i++){
                    var cls = (cells[i].className||'').split(' ');
                    var avail = true;
                    for (var j=0;j<bad.length;j++) {
                      if (cls.indexOf(bad[j])>=0) { avail = false; break; }
                    }
                    var lines = (cells[i].innerText||'').split('\n');
                    var t = '';
                    for (var k=0;k<lines.length;k++){
                      var ln = lines[k].trim();
                      if (ln) { t = ln; break; }
                    }
                    days.push({text:t, available:avail});
                  }
                  return {title:title, days:days};
                })()
            """.trimIndent()
            val raw = eval(js)
            if (raw == "null" || raw.isBlank()) break
            val month: MonthResult = json.decodeFromString(raw)
            results += month
            if (m < months - 1) {
                val clicked = eval(
                    "(function(){var b=document.querySelector('.btn_next'); " +
                    "if(b){b.click(); return 1;} return 0;})()"
                ).trim()
                if (clicked != "1") break
                delay(1200)
            }
        }
        return CheckResult(item, results)
    }

    private fun jsLiteral(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$esc\""
    }
}

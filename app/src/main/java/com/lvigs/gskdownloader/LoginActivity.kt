package com.lvigs.gskdownloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * In-app login (Instagram / Facebook) — user apne account se login kare,
 * cookies apne aap cookies.txt (Netscape format) me save ho jayengi.
 *
 * Har user apne phone par EK BAAR login karega — koi file-manager,
 * extension ya APK-rebuild ki zaroorat nahi. IG reels + FB HD (720p+)
 * isi login ke baad milte hain (bina login IG login-wall + FB sirf 360p).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var infoText: TextView
    private var site: String = "instagram"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        site = intent.getStringExtra("site") ?: "instagram"
        web = findViewById(R.id.loginWeb)
        infoText = findViewById(R.id.loginInfo)
        val doneBtn: Button = findViewById(R.id.loginDoneBtn)
        val closeBtn: Button = findViewById(R.id.loginCloseBtn)

        val isIG = site == "instagram"
        val startUrl = if (isIG) "https://www.instagram.com/accounts/login/"
        else "https://m.facebook.com/login"
        val homeHint = if (isIG) "instagram.com" else "facebook.com"
        infoText.text = if (isIG)
            "Instagram login karo — login ke baad neeche 'Done ✅ Save' dabao. Cookies auto-save hongi."
        else
            "Facebook login karo — login ke baad 'Done ✅ Save' dabao. HD quality unlock hogi."

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        try { cm.setAcceptThirdPartyCookies(web, true) } catch (_: Exception) {}

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Login ho gaya lagta hai (feed/home khul gaya) — user ko hint do
                try {
                    val u = (url ?: "").lowercase()
                    if (isIG && (u.contains("/feed") || u == "https://www.instagram.com/"
                                || u == "https://www.instagram.com")) {
                        infoText.text = "Login lag raha hai ✓ — ab 'Done ✅ Save' dabao."
                    }
                    if (!isIG && (u.contains("/home") || u.contains("m.facebook.com/?")
                                || u == "https://m.facebook.com/")) {
                        infoText.text = "Login lag raha hai ✓ — ab 'Done ✅ Save' dabao."
                    }
                } catch (_: Exception) {}
            }
        }
        web.loadUrl(startUrl)

        doneBtn.setOnClickListener {
            val saved = saveCookies(homeHint)
            if (saved) {
                Toast.makeText(this, "Login save ho gaya ✓ — ab video dobara fetch karo.", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, "Cookies nahi mili — pehle login poora karo, phir Done dabao.", Toast.LENGTH_LONG).show()
            }
        }
        closeBtn.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** WebView cookies -> Netscape cookies.txt me merge karke save karo.
     *  Returns true agar kam se kam 1 session cookie mili. */
    private fun saveCookies(homeHint: String): Boolean {
        return try {
            val cm = CookieManager.getInstance()
            val urls = if (site == "instagram")
                listOf("https://www.instagram.com/", "https://instagram.com/")
            else
                listOf("https://m.facebook.com/", "https://www.facebook.com/",
                    "https://facebook.com/", "https://fb.watch/")
            val domains = if (site == "instagram")
                listOf(".instagram.com") else listOf(".facebook.com", ".fb.watch")
            val newCookies = LinkedHashMap<String, String>()
            for (u in urls) {
                val raw = try { cm.getCookie(u) } catch (_: Exception) { null } ?: continue
                for (part in raw.split(";")) {
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank()) {
                        newCookies[kv[0].trim()] = kv[1].trim()
                    }
                }
            }
            // session wali key honi chahiye, warna login adhura hai
            val hasSession = if (site == "instagram")
                newCookies.containsKey("sessionid") || newCookies.containsKey("csrftoken")
            else
                newCookies.containsKey("c_user") || newCookies.containsKey("xs")
            if (newCookies.isEmpty()) return false

            val expiry = (System.currentTimeMillis() / 1000 + 365L * 24 * 3600).toString()
            val lines = ArrayList<String>()
            lines.add("# Netscape HTTP Cookie File")
            lines.add("# GSK Downloader — in-app login se auto-save (har user apna login)")
            // purani file ki doosre-domain cookies bachao (IG login FB cookies na udaye)
            for (f in listOf(File(filesDir, "cookies.txt"),
                File(getExternalFilesDir(null), "cookies.txt"))) {
                if (!f.exists()) continue
                try {
                    for (ln in f.readLines()) {
                        val t = ln.trim()
                        if (t.isEmpty() || t.startsWith("#")) continue
                        val cols = t.split("\t")
                        if (cols.size < 7) continue
                        val dom = cols[0].lowercase()
                        val isOurs = domains.any { dom.endsWith(it.lowercase()) }
                        if (!isOurs) {
                            // doosre site ki cookie — bachao (dup se bacho)
                            val key = dom + "|" + cols[5]
                            if (lines.none { it.contains("\t" + cols[5] + "\t") && it.startsWith(dom) }) {
                                lines.add(ln)
                            }
                        }
                    }
                } catch (_: Exception) {}
                break // pehli mili file hi kaafi (internal prefer)
            }
            for (d in domains) {
                for ((k, v) in newCookies) {
                    if (v.isEmpty()) continue
                    lines.add("$d\tTRUE\t/\tTRUE\t$expiry\t$k\t$v")
                }
            }
            val text = lines.joinToString("\n") + "\n"
            File(filesDir, "cookies.txt").writeText(text)
            try {
                File(getExternalFilesDir(null), "cookies.txt").writeText(text)
            } catch (_: Exception) {}
            // Session key na bhi mile to bhi cookies save karo (FB kabhi c_user der se deta hai),
            // lekin caller ko batao ki login pakka hai ya nahi.
            hasSession || newCookies.size >= 2
        } catch (_: Exception) { false }
    }
}

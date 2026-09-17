package eu.kanade.tachiyomi.extension.fr.banchanscan

import android.webkit.CookieManager
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Passe le challenge JavaScript Cloudflare du site (cf_clearance) en ouvrant
 * une WebView (qui exécute le JS du challenge), puis réessaie la requête OkHttp
 * avec le cookie partagé via CookieManager.
 */
object CloudflareBypass {

    private const val CLEARANCE_COOKIE = "cf_clearance"
    private const val TIMEOUT_SECONDS = 30L
    private const val POLL_INTERVAL_MS = 300L

    @Volatile
    private var failedOnce = false

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) return@Interceptor response
        response.close()

        if (!solve(request.url.toString(), chain.call())) {
            throw IOException("Bypass Cloudflare impossible — ouvre le site dans l'app pour passer le challenge.")
        }
        chain.proceed(request)
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.header("cf-mitigated") != null) return true
        if (response.code !in listOf(403, 429, 503)) return false
        return try {
            val body = response.peekBody(1_000_000L).string()
            body.contains("challenge-platform") ||
                body.contains("__CF\$cv\$params") ||
                body.contains("Just a moment")
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    private fun solve(url: String, call: Call): Boolean {
        if (failedOnce) return false
        val cookieManager = CookieManager.getInstance()
        return try {
            runWebViewBlocking<Unit>(call, timeout = TIMEOUT_SECONDS.seconds) {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockImages = true

                onPageFinished { pageUrl ->
                    poll(POLL_INTERVAL_MS.milliseconds) {
                        if (hasClearance(cookieManager, pageUrl)) resolve(Unit)
                    }
                }
                loadUrl(url)
            }
            val ok = hasClearance(cookieManager, url)
            if (ok) failedOnce = false else failedOnce = true
            ok
        } catch (_: Exception) {
            failedOnce = true
            false
        }
    }

    private fun hasClearance(cookieManager: CookieManager, url: String): Boolean {
        val cookies = cookieManager.getCookie(url) ?: return false
        return cookies.split(';').any { it.trim().startsWith("$CLEARANCE_COOKIE=") }
    }
}

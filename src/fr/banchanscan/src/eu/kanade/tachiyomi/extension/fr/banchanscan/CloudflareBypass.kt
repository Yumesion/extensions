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
 * avec le cookie partagé via CookieManager (AndroidCookieJar).
 *
 * La détection du challenge se fait sur le CORPS de la réponse ET sur le code
 * HTTP 403/429/503, indépendamment l'un de l'autre : Cloudflare peut servir sa
 * page de challenge avec un 200.
 *
 * Important : si le challenge exige une action humaine (case à cocher), la
 * WebView en arrière-plan ne peut pas le résoudre. L'utilisateur doit alors
 * ouvrir le site une fois dans l'app (WebView visible) pour poser le cookie
 * cf_clearance ; l'extension le récupère ensuite automatiquement. C'est
 * pourquoi on revérifie la présence du cookie à CHAQUE tentative, sans drapeau
 * d'échec persistant.
 */
object CloudflareBypass {

    private const val CLEARANCE_COOKIE = "cf_clearance"
    private const val TIMEOUT_SECONDS = 30L
    private const val POLL_INTERVAL_MS = 300L

    private val CHALLENGE_MARKERS = listOf(
        "challenge-platform",
        "__CF\$cv\$params",
        "Just a moment",
        "cf_chl_opt",
        "challenge-form",
        "cf_chl_prog",
        "Checking your browser",
        "Enable JavaScript",
        "cf-browser-verification",
        "Your request was blocked",
        "cf-mitigated",
    )

    fun interceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isChallenge(response)) return@Interceptor response
        response.close()

        if (!solve(request.url.toString(), chain.call())) {
            throw IOException(
                "Bypass Cloudflare impossible — ouvre banchanscan.fr une fois dans l'app " +
                    "(WebView) pour passer le challenge, puis réessaie.",
            )
        }

        val retry = chain.proceed(request)
        if (isChallenge(retry)) {
            retry.close()
            throw IOException("Bypass Cloudflare impossible — le challenge n'a pas été résolu.")
        }
        retry
    }

    private fun isChallenge(response: Response): Boolean {
        if (response.header("cf-mitigated") != null || response.header("cf-chl") != null) return true
        // Cloudflare sert parfois sa page de défi avec 403/429/503, parfois avec 200.
        if (response.code in listOf(403, 429, 503)) return true
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) return false
        return try {
            val body = response.peekBody(1_000_000L).string()
            CHALLENGE_MARKERS.any { body.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    private fun solve(url: String, call: Call): Boolean {
        val cookieManager = CookieManager.getInstance()
        // Déjà résolu (ex. challenge passé manuellement dans l'app) → on réessaie directement.
        if (hasClearance(cookieManager, url)) return true
        return try {
            runWebViewBlocking<Unit>(call, timeout = TIMEOUT_SECONDS.seconds) {
                javaScriptEnabled = true
                domStorageEnabled = true

                onPageFinished { pageUrl ->
                    poll(POLL_INTERVAL_MS.milliseconds) {
                        if (hasClearance(cookieManager, pageUrl)) resolve(Unit)
                    }
                }
                loadUrl(url)
            }
            hasClearance(cookieManager, url)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasClearance(cookieManager: CookieManager, url: String): Boolean {
        val cookies = cookieManager.getCookie(url) ?: return false
        return cookies.split(';').any { it.trim().startsWith("$CLEARANCE_COOKIE=") }
    }
}

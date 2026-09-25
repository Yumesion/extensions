package eu.kanade.tachiyomi.extension.fr.epsilonscannonofficiel

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.multisrc.pam.CheckBoxGroup
import eu.kanade.tachiyomi.multisrc.pam.Pam
import eu.kanade.tachiyomi.multisrc.pam.SortFilter
import eu.kanade.tachiyomi.multisrc.pam.TriStateGroupFilter
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.utils.applicationContext
import okhttp3.OkHttpClient
import okhttp3.Response
import rx.Observable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Source
abstract class EpsilonScanNonOfficiel : Pam() {

    override val popularFilters = FilterList(SortFilter("Sort", sortValues, Filter.Sort.Selection(3, false)))
    override val latestFilters = FilterList(SortFilter("Sort", sortValues, Filter.Sort.Selection(2, false)))

    override fun getFilterList() = FilterList(
        Filter.Header("La recherche textuelle ignore les filtres !"),
        Filter.Separator(),
        SortFilter("Sort", sortValues),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    private class GenreFilter : TriStateGroupFilter("Genres", genres)
    private class TypeFilter : TriStateGroupFilter("Types", type)
    private class StatusFilter : CheckBoxGroup("Status", status)

    // Le lecteur est derrière un challenge Cloudflare Turnstile. L'interceptor Cloudflare du
    // host tente une résolution headless qui échoue (~30 s) puis lève « Attestation refused » :
    // on l'écarte et on résout le challenge via une WebView réelle (cf_clearance partagé avec
    // le client de l'app via CookieManager).
    private val readerClient: OkHttpClient by lazy {
        client.newBuilder()
            .apply { interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" } }
            .build()
    }

    private val sessionWarmedUp = AtomicBoolean(false)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val chapterUrl = "$baseUrl${chapter.url}"

        fun fetch(): Response? = try {
            val resp = readerClient.newCall(pageListRequest(chapter)).execute()
            val body = resp.peekBody(2048).string()
            if (resp.isSuccessful && !body.contains("Just a moment") && !body.contains("challenges.cloudflare")) {
                resp
            } else {
                resp.close()
                null
            }
        } catch (_: Exception) {
            null
        }

        var resp = fetch()
        if (resp == null) {
            warmupWebViewSession(chapterUrl)
            resp = fetch()
        }
        if (resp == null) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(applicationContext, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url_key", chapterUrl)
                    putExtra("source_key", id)
                    putExtra("title_key", "Résolvez le challenge Cloudflare, fermez la WebView et réouvrez le chapitre.")
                }
                applicationContext.startActivity(intent)
            } catch (_: Exception) {
                throw Exception("Résolvez le challenge Cloudflare depuis la WebView puis réouvrez le chapitre.")
            }

            for (attempt in 1..CF_MAX_POLLS) {
                Thread.sleep(CF_POLL_INTERVAL_MS)
                resp = fetch()
                if (resp != null) break
            }
            if (resp == null) {
                sessionWarmedUp.set(false)
                throw Exception("Résolvez le challenge Cloudflare, fermez la WebView et réouvrez le chapitre.")
            }
        }

        return resp.use { Observable.just(pageListParse(it)) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun warmupWebViewSession(chapterUrl: String) {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            val wv = WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mainHandler.postDelayed({
                        runCatching {
                            view?.stopLoading()
                            view?.destroy()
                        }
                        latch.countDown()
                    }, WARMUP_SETTLE_MS)
                }
            }
            wv.loadUrl(chapterUrl)
        }

        try {
            if (!latch.await(WARMUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                sessionWarmedUp.set(false)
            }
        } catch (_: InterruptedException) {
            sessionWarmedUp.set(false)
        }
    }

    companion object {
        private const val CF_POLL_INTERVAL_MS = 5000L
        private const val CF_MAX_POLLS = 15
        private const val WARMUP_SETTLE_MS = 1500L
        private const val WARMUP_TIMEOUT_SECONDS = 15L
    }
}

private val sortValues = listOf(
    "New Series" to "date",
    "Trending" to "trending",
    "Recently Updated" to "recently",
    "Most Views" to "views",
    "A-Z" to "alphabetical",
)

private val genres = listOf(
    "Action" to "action",
    "Ahegao" to "ahegao",
    "Anal" to "anal",
    "Anime bl" to "anime-bl",
    "Arts martiaux" to "arts-martiaux",
    "Aventure" to "aventure",
    "Bdsm" to "bdsm",
    "Bondage" to "bondage",
    "Boys love" to "boys-love",
    "Bureau" to "bureau",
    "Campus" to "campus",
    "Comédie" to "comedie",
    "Comics" to "comics",
    "Cosplay" to "cosplay",
    "Coup d'un soir" to "coup-dun-soir",
    "Dark skin" to "dark-skin",
    "Démon/démone" to "demondemone",
    "Différence d'âge" to "difference-dage",
    "Doujinshi" to "doujinshi",
    "Drame" to "drame",
    "Échangisme" to "echangisme",
    "Elf" to "elf",
    "Espion" to "espion",
    "Exhibitionniste" to "exhibitionniste",
    "Fantaisie" to "fantaisie",
    "Fantastique" to "fantastique",
    "Fétichisme" to "fetichisme",
    "Furry" to "furry",
    "Gangster" to "gangster",
    "Gender bender" to "gender-bender",
    "Girls love" to "girls-love",
    "Gros seins" to "gros-seins",
    "Guideverse" to "guideverse",
    "Hardcore" to "hardcore",
    "Harem" to "harem",
    "Historique" to "historique",
    "Horreur" to "horreur",
    "Hypnose" to "hypnose",
    "Ia" to "ia",
    "Immoral" to "immoral",
    "Isekai" to "isekai",
    "Jeux vidéo" to "jeux-video",
    "Josei" to "josei",
    "Magie" to "magie",
    "Manga bl" to "manga-bl",
    "Manga h" to "manga-h",
    "Manga josei" to "manga-josei",
    "Mature" to "mature",
    "Médical" to "medical",
    "Milf" to "milf",
    "Mini-série" to "mini-serie",
    "Moderne" to "moderne",
    "Muscle" to "muscle",
    "Mystère" to "mystere",
    "Noblesse" to "noblesse",
    "Non-censuré" to "non-censure",
    "Novel" to "novel",
    "Ntr" to "ntr",
    "Omégaverse" to "omegaverse",
    "One shot" to "one-shot",
    "Percing" to "percing",
    "Plan à 3" to "plan-a-3",
    "Pornhwa" to "pornhwa",
    "Professeur" to "professeur",
    "Psychologique" to "psychologique",
    "Réincarnation" to "reincarnation",
    "Romance" to "romance",
    "Science-fiction" to "science-fiction",
    "Showbiz" to "showbiz",
    "Smut" to "smut",
    "Spanking" to "spanking",
    "Sports" to "sports",
    "Succube" to "succube",
    "Surnaturel" to "surnaturel",
    "Système" to "systeme",
    "Thriller" to "thriller",
    "Tragédie" to "tragedie",
    "Tranche de vie" to "tranche-de-vie",
    "Triangle amoureux" to "triangle-amoureux",
    "Tsundere" to "tsundere",
    "Vampire" to "vampire",
    "Vengeance" to "vengeance",
    "Vie scolaire" to "vie-scolaire",
    "Webtoon" to "webtoon",
)

private val type = listOf(
    "Anime bl" to "anime-bl",
    "Boys love" to "boys-love",
    "Doujinshi" to "doujinshi",
    "Girls love" to "girls-love",
    "Hentai" to "hentai",
    "Josei" to "josei",
    "Manga" to "manga",
    "Manga bl" to "manga-bl",
    "Manga h" to "manga-h",
    "Manga josei" to "manga-josei",
    "Manhwa" to "manhwa",
    "Manwha" to "manwha",
    "Novel" to "novel",
    "Other" to "other",
    "Pornhwa" to "pornhwa",
    "Seinen" to "seinen",
)

private val status = listOf(
    "Ongoing" to "ongoing",
    "Finished" to "finished",
    "Dropped" to "dropped",
    "On Hold" to "onhold",
    "Upcoming" to "upcoming",
)

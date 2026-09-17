package eu.kanade.tachiyomi.extension.fr.banchanscan

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Banchan Scan (banchanscan.fr) — site sous React Server Components (Vinext) :
 * le contenu (catalogue, fiches, chapitres) est rendu CÔTÉ CLIENT par JavaScript.
 * OkHttp (sans JS) ne reçoit donc que la coquille HTML (titre + données RSC
 * sérialisées), sans aucune œuvre. On charge donc chaque page dans une WebView
 * (qui exécute le JS et rend le contenu), puis on extrait le DOM rendu.
 */
@Source
abstract class BanchanScan : KeiSource() {

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = renderHtml("$baseUrl/oeuvres")
        return MangasPage(document.select(CARD_SELECTOR).map(::mangaFromElement), hasNextPage = false)
    }

    // ============================== Latest ================================

    // Pas de flux « dernières sorties » dédié : on retombe sur le catalogue.
    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ============================== Search ================================

    // La recherche du site est aussi côté client (filtrage de la liste rendue) :
    // pas d'endpoint serveur. On récupère le catalogue rendu et on filtre par titre.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val document = renderHtml("$baseUrl/oeuvres")
        val results = document.select(CARD_SELECTOR)
            .map(::mangaFromElement)
            .filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(results, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val index = segments.indexOf("webtoon")
        if (index == -1 || index + 1 >= segments.size) return null
        val slug = segments[index + 1]
        return SManga.create().apply { setUrlWithoutDomain("$baseUrl/webtoon/$slug") }
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = renderHtml("$baseUrl${manga.url}")
        return SMangaUpdate(
            manga = if (fetchDetails) parseMangaDetails(document, manga) else manga,
            chapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1")?.text()?.trim() ?: manga.title
        thumbnail_url = manga.thumbnail_url
        author = document.select("a[href^='/oeuvres/auteur/']").joinToString(" / ") { it.text().trim() }
            .ifBlank { null }
        artist = document.select("a[href^='/oeuvres/artiste/']").joinToString(" / ") { it.text().trim() }
            .ifBlank { null }
        genre = document.select("span[class*='text-white/70']").joinToString(", ") { it.text().trim() }
            .ifBlank { null }
        description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        status = when (document.selectFirst("span[aria-label='Statut']")?.text()?.trim()?.lowercase()) {
            "terminé", "complete", "completed" -> SManga.COMPLETED
            "en cours", "ongoing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val mangaSlug = mangaUrl.substringAfterLast("/")
        return document.select("a[href^='/webtoon/$mangaSlug/']")
            .map(::chapterFromElement)
            .distinctBy { it.url }
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.selectFirst("p.text-sm.font-semibold")?.text()?.trim() ?: "Chapitre"
        date_upload = parseRelativeDate(element.text())
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = renderHtml("$baseUrl${chapter.url}")
        return document.select("img[src*='/chapters/']").mapIndexed { index, img ->
            Page(index, imageUrl = img.absUrl("src"))
        }
    }

    // ============================== Helpers ===============================

    /**
     * Charge [url] dans une WebView (qui exécute le JavaScript de rendu), attend
     * que React ait fini de rendre (taille du DOM stable sur 2 sondages consécutifs),
     * puis renvoie le DOM complet. Un simple délai fixe ou un seuil de taille seul
     * extrayait trop tôt (1 œuvre au lieu de 24, aucun chapitre).
     */
    private suspend fun renderHtml(url: String): Document {
        val html = runWebView<String>(timeout = 45.seconds) {
            javaScriptEnabled = true
            domStorageEnabled = true

            onPageFinished { _ ->
                var lastLength = 0
                var stable = 0
                poll(1000.milliseconds) {
                    evaluateJs("document.documentElement.outerHTML.length") { value ->
                        val len = value.toIntOrNull() ?: 0
                        if (len == lastLength) {
                            stable++
                        } else {
                            lastLength = len
                            stable = 0
                        }
                        if (len > 20000 && stable >= 2) {
                            evaluateJs("document.documentElement.outerHTML") { rendered ->
                                runCatching { rendered.parseAs<String>() }.getOrNull()
                                    ?.takeIf { it.length > 20000 }
                                    ?.let { resolve(it) }
                            }
                        }
                    }
                }
            }
            loadUrl(url)
        }
        return Jsoup.parse(html)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst("h3")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: "Sans titre"
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("src")
            if (src.isNotBlank() && !src.startsWith("data:")) img.absUrl("src") else null
        }
    }

    // Date relative française vue dans la liste des chapitres (« il y a 14 h »,
    // « il y a 2 jours », « il y a 1 mois », …) → epoch millis approximatif.
    private fun parseRelativeDate(text: String): Long {
        val match = RELATIVE_DATE_REGEX.find(text) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase()
        val millis = when {
            unit.startsWith("h") -> amount * 3_600_000L
            unit.startsWith("j") -> amount * 86_400_000L
            unit.startsWith("sem") -> amount * 604_800_000L
            unit.startsWith("mois") -> amount * 2_592_000_000L // ~30 jours
            unit.startsWith("an") -> amount * 31_536_000_000L
            else -> 0L
        }
        return if (millis == 0L) 0L else System.currentTimeMillis() - millis
    }

    companion object {
        private const val CARD_SELECTOR = "a[href^=\"/webtoon/\"]"
        private val RELATIVE_DATE_REGEX = Regex("""il y a (\d+)\s+([a-zéû]+)""", RegexOption.IGNORE_CASE)
    }
}

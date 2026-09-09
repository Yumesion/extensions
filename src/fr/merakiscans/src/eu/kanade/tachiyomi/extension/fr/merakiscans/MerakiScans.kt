package eu.kanade.tachiyomi.extension.fr.merakiscans

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset

@Source
abstract class MerakiScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/catalogue/?sort=popular").asJsoup()
        return MangasPage(document.select("a.cat-card").map(::mangaFromElement), hasNextPage = false)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/catalogue/?sort=-updated").asJsoup()
        return MangasPage(document.select("a.cat-card").map(::mangaFromElement), hasNextPage = false)
    }

    // ============================== Search ================================

    // La recherche du site est cassée côté serveur : /api/search/ renvoie 500
    // pour toutes les requêtes. La recherche par URL reste possible (getMangaByUrl).
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), hasNextPage = false)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val mangaIndex = segments.indexOf("manga")
        if (mangaIndex == -1 || mangaIndex + 1 >= segments.size) return null
        val slug = segments[mangaIndex + 1]
        return SManga.create().apply { setUrlWithoutDomain("$baseUrl/manga/$slug/") }
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()
        return SMangaUpdate(
            manga = if (fetchDetails) parseMangaDetails(document, manga) else manga,
            chapters = if (fetchChapters) parseChapterList(document, manga.url) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.manga-title")?.text()?.trim() ?: manga.title
        thumbnail_url = manga.thumbnail_url
        author = document.selectFirst("p.manga-author strong")?.text()?.trim()
        genre = document.select("div.genres span.genre-tag").joinToString(", ") { it.text().trim() }
            .ifBlank { null }
        description = document.selectFirst(".synopsis-text")?.text()?.trim()
        status = when {
            document.selectFirst(".badge-status-complete") != null -> SManga.COMPLETED
            document.selectFirst(".badge-status-ongoing") != null -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val chapters = document.select("div.chapters-list a.chapter-row").map(::chapterFromElement).toMutableList()

        // La liste des chapitres est paginée côté serveur (10 par page). Le site charge les
        // pages suivantes via `?order=desc&page=N` avec le header X-Requested-With: XMLHttpRequest.
        val lastPage = document.select(".pagination a.page-btn[href]")
            .mapNotNull { it.attr("href").let { href -> PAGE_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull() } }
            .maxOrNull() ?: 1

        for (page in 2..lastPage) {
            val pageUrl = "$baseUrl$mangaUrl".toHttpUrl().newBuilder()
                .addQueryParameter("order", "desc")
                .addQueryParameter("page", page.toString())
                .build()
            chapters += client.get(pageUrl, XHR_HEADERS).asJsoup()
                .select("div.chapters-list a.chapter-row")
                .map(::chapterFromElement)
        }

        return chapters
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.selectFirst(".chapter-num")?.text()?.trim() ?: "Chapitre"
        date_upload = element.selectFirst(".chapter-date")?.text()?.trim()?.let(::parseDate) ?: 0L
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        return document.select("#pages-container img.page-img").mapIndexed { index, img ->
            Page(index, imageUrl = img.absUrl("src"))
        }
    }

    // ============================== Helpers ===============================

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".cat-card-title")?.text()?.trim() ?: "Sans titre"
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("src")
            if (src.isNotBlank() && !src.startsWith("data:")) img.absUrl("src") else null
        }
    }

    private fun parseDate(text: String): Long = try {
        val parts = text.trim().split(" ")
        if (parts.size < 3) return 0L
        val day = parts[0].toInt()
        val month = MONTHS[parts[1].lowercase()] ?: return 0L
        val year = parts[2].toInt()
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    companion object {
        private val XHR_HEADERS = Headers.headersOf("X-Requested-With", "XMLHttpRequest")
        private val PAGE_REGEX = Regex("""page=(\d+)""")

        // Abréviations de mois françaises vues sur le site (« Jui » = juillet, format Django « b »).
        private val MONTHS = mapOf(
            "jan" to 1, "janv" to 1, "fev" to 2, "fevr" to 2, "févr" to 2,
            "mar" to 3, "mars" to 3, "avr" to 4, "mai" to 5,
            "jui" to 7, "juin" to 6, "juil" to 7, "juillet" to 7,
            "aou" to 8, "aout" to 8, "août" to 8, "sep" to 9, "sept" to 9,
            "oct" to 10, "nov" to 11, "dec" to 12, "déc" to 12,
        )
    }
}

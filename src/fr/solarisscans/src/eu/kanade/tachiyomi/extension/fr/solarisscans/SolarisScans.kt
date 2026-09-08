package eu.kanade.tachiyomi.extension.fr.solarisscans

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
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class SolarisScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga/?catalog_page=$page").asJsoup()
        return MangasPage(
            document.select(".solaris-catalog-card").map(::mangaFromElement),
            hasNextPage = document.selectFirst("a.next.page-numbers") != null,
        )
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga/?solaris_section=latest&catalog_page=$page").asJsoup()
        return MangasPage(
            document.select(".solaris-catalog-card").map(::mangaFromElement),
            hasNextPage = document.selectFirst("a.next.page-numbers") != null,
        )
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("catalog_q", query)
            .addQueryParameter("catalog_page", page.toString())
            .build()
        val document = client.get(url).asJsoup()
        return MangasPage(
            document.select(".solaris-catalog-card").map(::mangaFromElement),
            hasNextPage = document.selectFirst("a.next.page-numbers") != null,
        )
    }

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
            chapters = if (fetchChapters) parseChapterList(manga) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1")?.text() ?: manga.title
        thumbnail_url = document.selectFirst(".sz-ri-main img")?.absUrl("src") ?: manga.thumbnail_url
        val authors = document.select(".sz-ri-byline strong")
        author = authors.firstOrNull()?.text()
        artist = authors.getOrNull(1)?.text()
        genre = document.select(".sz-ri-main-genres span").joinToString(", ") { it.text() }.ifBlank { null }
        description = document.selectFirst(".sz-ri-description")?.text()
        status = when (document.sideInfo("Statut")) {
            "En cours" -> SManga.ONGOING
            "Terminé" -> SManga.COMPLETED
            "En pause", "Pause" -> SManga.ON_HIATUS
            "Abandonné" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.sideInfo(label: String): String? = select(".sz-ri-side-info div")
        .firstOrNull { it.selectFirst("span")?.text()?.trim() == label }
        ?.selectFirst("strong")
        ?.text()

    private suspend fun parseChapterList(manga: SManga): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val document = client.get("$baseUrl${manga.url}?chapters_page=$page").asJsoup()
            val pageChapters = document.select("a.sz-chapter-card").map(::chapterFromElement)
            if (pageChapters.isEmpty()) break
            chapters.addAll(pageChapters)
            val next = document.select("a.sz-chapter-pagination__btn").firstOrNull { it.text().contains("Suivant") }
            if (next == null || !next.hasAttr("href")) break
            page++
        }
        return chapters
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.selectFirst("strong")?.text() ?: "Chapitre"
        date_upload = element.attr("data-recent").toLongOrNull()?.times(1000) ?: 0L
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        val script = document.selectFirst("#chapter_preloaded_images")?.data() ?: return emptyList()
        val urls = PRELOADED_REGEX.find(script)?.groupValues?.get(1)
            ?.let { json.decodeFromString<List<String>>(it) }
            ?: return emptyList()
        return urls.mapIndexed { index, url -> Page(index, imageUrl = "$baseUrl$url") }
    }

    // ============================== Helpers ===============================

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h2 a")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text()
        thumbnail_url = element.selectFirst(".solaris-catalog-card__cover img")?.absUrl("src")
    }
}

private val PRELOADED_REGEX = Regex("""var chapter_preloaded_images\s*=\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)

private val json = Json { ignoreUnknownKeys = true }

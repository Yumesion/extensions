package eu.kanade.tachiyomi.extension.fr.blackarmy

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class BlackArmy : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga/page/$page/").asJsoup()
        return MangasPage(
            document.select(".manga-card").map(::mangaFromElement),
            hasNextPage = document.selectFirst("a.next.page-numbers") != null,
        )
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/radar/page/$page/").asJsoup()
        return MangasPage(
            document.select(".radar-card").map(::mangaFromRadarElement),
            hasNextPage = document.selectFirst("a.next.page-numbers") != null,
        )
    }

    // ============================== Search ================================

    // La recherche du site renvoie les titres sans lien (bug du thème), donc
    // pas de recherche texte exploitable. La recherche par URL reste possible
    // via getMangaByUrl.
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
            chapters = if (fetchChapters) parseChapterList(document) else chapters,
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.serie-title")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content") ?: manga.thumbnail_url
        author = document.metaValue("Auteur")
        artist = document.metaValue("Artiste")
        genre = document.select(".genre-links .genre-link").joinToString(", ") { it.text() }.ifBlank { null }
        description = document.selectFirst(".synopsis-content p")?.text()
        status = when (document.selectFirst(".stat-item:has(i.fa-clock)")?.text()?.trim()) {
            "En cours" -> SManga.ONGOING
            "Terminé" -> SManga.COMPLETED
            "En pause", "Pause" -> SManga.ON_HIATUS
            "Abandonné" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.metaValue(label: String): String? = select(".meta-group")
        .firstOrNull { it.selectFirst(".meta-label")?.text()?.trim() == label }
        ?.selectFirst(".meta-value")
        ?.text()

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".chapter-card")
        .filter { it.selectFirst(".chapter-vip-badge") == null } // masque les chapitres VIP
        .map(::chapterFromElement)

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.selectFirst(".chapter-card-title")?.text()?.trim() ?: "Chapitre"
        date_upload = element.selectFirst(".chapter-card-date")?.text()?.let(::parseDate) ?: 0L
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        val script = document.select("script").firstOrNull { "check_chapter" in it.data() }?.data()
            ?: return emptyList()
        val id = CHAPTER_ID_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
        val nonce = CHECK_NONCE_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()

        val body = FormBody.Builder()
            .add("action", "check_chapter")
            .add("chapitre_id", id)
            .add("nonce", nonce)
            .build()

        val data = client.post("$baseUrl/wp-admin/admin-ajax.php", body)
            .parseAs<CheckChapterResponse>()
            .data
            ?: return emptyList()
        if (data.locked) return emptyList()

        return data.images
            .filterNot { it.endsWith("/lss.jpg") } // page de crédits intercalée avant la page 1
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    // ============================== Helpers ===============================

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a.manga-title")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = link.text().trim()
        thumbnail_url = element.selectFirst("img")?.coverUrl()
    }

    private fun mangaFromRadarElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a.radar-cover-wrap")!!
        setUrlWithoutDomain(link.absUrl("href"))
        title = element.selectFirst(".radar-manga-title")?.text()?.trim() ?: "Sans titre"
        thumbnail_url = element.selectFirst("a.radar-cover-wrap img")?.coverUrl()
    }

    private fun Element.coverUrl(): String? {
        val lazy = attr("data-lazy-src")
        if (lazy.isNotBlank()) return absUrl("data-lazy-src")
        val src = attr("src")
        return if (src.isNotBlank() && !src.startsWith("data:")) absUrl("src") else null
    }

    private fun parseDate(text: String): Long = try {
        LocalDate.parse(text.trim(), DATE_FORMATTER).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

@Serializable
private class CheckChapterResponse(
    val success: Boolean,
    val data: CheckChapterData? = null,
)

@Serializable
private class CheckChapterData(
    val locked: Boolean = false,
    val images: List<String> = emptyList(),
)

private val CHAPTER_ID_REGEX = Regex("""chapitre_id', '(\d+)'""")
private val CHECK_NONCE_REGEX = Regex("""check_chapter[\s\S]{0,200}?nonce', '([a-f0-9]{10})'""")

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.FRENCH)

package eu.kanade.tachiyomi.extension.fr.yureiscan

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Source
abstract class YureiScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/manga_list.php").asJsoup()
        val mangas = document.select("div.manga-card-wrapper a.manga-card").map(::mangaFromElement)
        return MangasPage(mangas, hasNextPage = false)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select("a.sortie-item").mapNotNull(::mangaFromLatestElement)
        return MangasPage(mangas, hasNextPage = false)
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        val url = "$baseUrl/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("adult", "1")
            .build()
        val mangas = client.get(url).parseAs<List<SearchResult>>().map { result ->
            SManga.create().apply {
                title = result.title
                thumbnail_url = result.coverImage
                setUrlWithoutDomain("$baseUrl/manga.php?id=${result.id}")
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = url.queryParameter("id") ?: return null
        return SManga.create().apply { setUrlWithoutDomain("$baseUrl/manga.php?id=$id") }
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
        title = document.selectFirst("h1.manga-title")?.text() ?: manga.title
        thumbnail_url = document.selectFirst("img.manga-cover")?.absUrl("src") ?: manga.thumbnail_url
        author = document.infoValue("Auteur")
        artist = document.infoValue("Artiste")
        genre = document.select("span.genre-tag").joinToString(", ") { it.text() }.ifBlank { null }
        description = document.selectFirst(".synopsis-section p")?.text()
        val statusText = document.infoValue("Statut").orEmpty()
        status = when {
            "Terminé" in statusText -> SManga.COMPLETED
            "Abandonné" in statusText -> SManga.CANCELLED
            "En cours" in statusText -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun Document.infoValue(label: String): String? = select(".manga-info-list li")
        .firstOrNull { it.selectFirst("strong")?.text()?.contains(label) == true }
        ?.text()
        ?.substringAfter(":")
        ?.trim()

    private fun parseChapterList(document: Document): List<SChapter> = document.select("a.chapter-card").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.absUrl("href"))
            name = element.selectFirst(".chapter-number")?.text() ?: "Chapitre"
            date_upload = element.selectFirst(".chapter-date")?.text()?.let(::parseDate) ?: 0L
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get("$baseUrl${chapter.url}")
        .asJsoup()
        .select("img.page-image[data-src]")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("data-src")) }

    // ============================== Helpers ===============================

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".manga-title h2")!!.text()
        thumbnail_url = element.selectFirst(".manga-image-container img")?.absUrl("src")
    }

    private fun mangaFromLatestElement(element: Element): SManga? {
        val title = element.selectFirst(".sortie-title")?.text() ?: return null
        val mangaId = MANGA_ID_REGEX.find(element.absUrl("href"))?.groupValues?.get(1) ?: return null
        return SManga.create().apply {
            this.title = title
            thumbnail_url = element.selectFirst("img.sortie-thumb")?.absUrl("src")
            setUrlWithoutDomain("$baseUrl/manga.php?id=$mangaId")
        }
    }

    private fun parseDate(text: String): Long = LocalDate.parse(text.trim(), DATE_FORMATTER).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

@Serializable
private class SearchResult(
    val id: Int,
    val title: String,
    @SerialName("cover_image") val coverImage: String? = null,
)

private val MANGA_ID_REGEX = Regex("""id=(\d+)""")

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy")

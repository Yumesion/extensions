package eu.kanade.tachiyomi.extension.fr.banchanscan

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
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.Normalizer
import java.time.OffsetDateTime

/**
 * Banchan Scan (banchanscan.fr) — catalogue de webtoons/mangas français.
 *
 * Le site est rendu par React Server Components (Vinext), mais toutes les données
 * (œuvres, chapitres, pages) transitent par une API REST propre, un proxy Supabase :
 *
 *   GET /api/data/webtoons?select=*&is_adult=eq.false&order=created_at.desc&limit=24&offset=0
 *   GET /api/data/chapters?webtoon_id=eq.<id>&select=*&order=chapter_number.desc
 *   GET /api/data/chapter_pages?chapter_id=eq.<id>&select=*&order=sort_order.asc
 *   GET /api/home-data  → { webtoons, chapters, … } (flux « dernières sorties »)
 *
 * On parle donc directement à cette API (JSON) : pas de WebView, pas de parsing HTML,
 * pas de contournement Cloudflare nécessaire.
 */
@Source
abstract class BanchanScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(2)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val webtoons = client.get(
            apiUrl("webtoons")
                .addQueryParameter("select", LIST_SELECT)
                .addQueryParameter("is_adult", "eq.false")
                .addQueryParameter("order", "created_at.desc")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
                .build(),
        ).parseAs<List<Webtoon>>()
        return MangasPage(webtoons.map(::webtoonToManga), hasNextPage = webtoons.size == PAGE_SIZE)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val home = client.get("$baseUrl/api/home-data").parseAs<HomeData>()
        val byId = home.webtoons.associateBy { it.id }
        val latest = home.chapters
            .mapNotNull { byId[it.webtoonId] }
            .filterNot { it.isAdult }
            .distinctBy { it.id }
            .map(::webtoonToManga)
        return MangasPage(latest, hasNextPage = false)
    }

    // ============================== Search ================================

    // L'API n'expose pas de recherche serveur (l'opérateur `ilike` est rejeté par le
    // proxy). Le catalogue SFW est petit : on le charge et on filtre localement.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val webtoons = client.get(
            apiUrl("webtoons")
                .addQueryParameter("select", LIST_SELECT)
                .addQueryParameter("is_adult", "eq.false")
                .build(),
        ).parseAs<List<Webtoon>>()
        val results = webtoons.filter { it.matches(query) }.map(::webtoonToManga)
        return MangasPage(results, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments
        val index = segments.indexOf("webtoon")
        if (index == -1 || index + 1 >= segments.size) return null
        val slug = segments[index + 1]
        val webtoons = client.get(
            apiUrl("webtoons").addQueryParameter("select", LIST_SELECT).build(),
        ).parseAs<List<Webtoon>>()
        return webtoons.firstOrNull { it.title.slugify() == slug }?.let(::webtoonToManga)
    }

    // ============================== Details ===============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val webtoonId = manga.url
        val webtoon = if (fetchDetails) {
            client.get(
                apiUrl("webtoons")
                    .addQueryParameter("select", "*")
                    .addQueryParameter("id", "eq.$webtoonId")
                    .build(),
            ).parseAs<List<Webtoon>>().firstOrNull()
        } else {
            null
        }
        val chapterList = if (fetchChapters) {
            client.get(
                apiUrl("chapters")
                    .addQueryParameter("select", "*")
                    .addQueryParameter("webtoon_id", "eq.$webtoonId")
                    .build(),
            ).parseAs<List<Chapter>>()
                .sortedByDescending { it.chapterNumber.toFloatOrNull() ?: 0f }
                .map(::chapterToSChapter)
        } else {
            chapters
        }
        return SMangaUpdate(
            manga = webtoon?.let(::webtoonToManga) ?: manga,
            chapters = chapterList,
        )
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = client.get(
            apiUrl("chapter_pages")
                .addQueryParameter("select", "*")
                .addQueryParameter("chapter_id", "eq.${chapter.url}")
                .build(),
        ).parseAs<List<ChapterPage>>().sortedBy { it.sortOrder }
        return pages.mapIndexed { index, page -> Page(index, imageUrl = page.imageUrl) }
    }

    // ============================== Helpers ===============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/webtoon/${manga.title.slugify()}"

    private fun apiUrl(table: String): HttpUrl.Builder = "$baseUrl/api/data/$table".toHttpUrl().newBuilder()

    private fun webtoonToManga(webtoon: Webtoon): SManga = SManga.create().apply {
        url = webtoon.id
        title = webtoon.title
        thumbnail_url = webtoon.coverUrl
        description = webtoon.description
        author = webtoon.author
        artist = webtoon.artist
        genre = webtoon.genres.ifEmpty { webtoon.categories }.joinToString(", ").ifBlank { null }
        status = webtoon.status.toStatus()
    }

    private fun chapterToSChapter(chapter: Chapter): SChapter = SChapter.create().apply {
        url = chapter.id
        name = buildString {
            append("Chapitre ")
            append(chapter.chapterNumber)
            chapter.chapterTitle.cleanTitle()?.let { append(" : ").append(it) }
        }
        chapter_number = chapter.chapterNumber.toFloatOrNull() ?: -1f
        date_upload = chapter.publishedAt?.let(::parseDate) ?: 0L
        scanlator = chapter.uploaderName
    }

    private fun Webtoon.matches(query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        val fields = buildList {
            add(title)
            add(altTitle)
            add(author)
            add(artist)
            addAll(alternativeTitles)
        }.filterNotNull()
        return fields.any { it.contains(q, ignoreCase = true) }
    }

    private fun String?.cleanTitle(): String? = this
        ?.substringBefore('\u2063') // séparateur invisible ajouté par le site avant des métadonnées
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun String?.toStatus(): Int = when (this?.trim()?.lowercase()) {
        "terminé" -> SManga.COMPLETED
        "en cours" -> SManga.ONGOING
        "licenciée" -> SManga.LICENSED
        "en pause" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private fun parseDate(text: String): Long = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    // Slug identique à celui du site (/webtoon/<slug>) : minuscules, accents retirés,
    // caractères non alphanumériques → tiret, tirets multiples condensés.
    private fun String.slugify(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    companion object {
        private const val PAGE_SIZE = 24
        private const val LIST_SELECT =
            "id,title,cover_url,description,genres,categories,status,author,artist,alt_title,alternative_titles"
    }
}

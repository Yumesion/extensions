package eu.kanade.tachiyomi.extension.fr.astralmanganonofficiel

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
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class AstralMangaNonOfficiel : KeiSource() {

    private val rscHeaders get() = headersBuilder().add("RSC", "1").build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(8, 1.seconds)
        addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("Referer", "$baseUrl/").build())
        }
    }

    // ========================== Popular / Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = mangaList(page, "note", "desc")

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaList(page, "publishDate", "desc")

    private suspend fun mangaList(page: Int, sortBy: String, sortOrder: String): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", "12")
            addQueryParameter("sortBy", sortBy)
            addQueryParameter("sortOrder", sortOrder)
            addQueryParameter("includeMode", "and")
            addQueryParameter("excludeMode", "or")
        }.build()
        val dto = client.get(url).parseAs<MangaResponseDto>()
        val mangas = dto.mangas.map { it.toSManga() }
        return MangasPage(mangas, hasNextPage = (dto.mangas.size >= 12) && (mangas.size < dto.total))
    }

    // ========================== Search ==========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/mangas".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", "12")

            if (query.isNotBlank()) {
                addQueryParameter("query", query)
            }

            var sortBy = "title"
            var sortOrder = "asc"

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        sortBy = filter.toUriPart()
                        sortOrder = if (sortBy == "title") "asc" else "desc"
                    }

                    is StatusFilter -> {
                        val statusValue = filter.toUriPart()
                        if (statusValue.isNotBlank()) addQueryParameter("status", statusValue)
                    }

                    is TypeFilter -> {
                        val typeValue = filter.toUriPart()
                        if (typeValue.isNotBlank()) addQueryParameter("type", typeValue)
                    }

                    is GenreFilter -> {
                        filter.state.filter { it.state }.forEach { addQueryParameter("tags", it.name) }
                    }

                    else -> {}
                }
            }

            addQueryParameter("sortBy", sortBy)
            addQueryParameter("sortOrder", sortOrder)
            addQueryParameter("includeMode", "and")
            addQueryParameter("excludeMode", "or")
        }.build()
        val dto = client.get(url).parseAs<MangaResponseDto>()
        val mangas = dto.mangas.map { it.toSManga() }
        return MangasPage(mangas, hasNextPage = (dto.mangas.size >= 12) && (mangas.size < dto.total))
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    // ========================== Details + Chapters ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUuid = manga.url.substringAfterLast("/")
        val rscBody = fetchMangaRsc(manga.url)

        val mangaDto = rscBody.extractNextJsRsc<MangaDto> {
            it is JsonObject && it["urlId"]?.jsonPrimitive?.contentOrNull == mangaUuid
        }

        val updatedManga = if (fetchDetails) mangaDto?.toSManga() ?: manga else manga

        val updatedChapters = if (fetchChapters) {
            rscBody.extractNextJsRsc<List<RscChapterDto>>()
                .orEmpty()
                .filter { it.mangaId == mangaDto?.id }
                .distinctBy { it.id }
                .map { it.toSChapter(mangaUuid) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchMangaRsc(url: String): String {
        val first = client.get(baseUrl + url, rscHeaders).use { it.body.string() }
        val hasChapters = first.extractNextJsRsc<List<RscChapterDto>>()?.isNotEmpty() == true
        if (hasChapters) return first

        // RSC data can be partial on first load; retry sans le ?_timestamp (qui casse
        // le RSC) en forçant seulement le rechargement via Cache-Control.
        return client.get(baseUrl + url, rscHeaders, cacheControl = CacheControl.FORCE_NETWORK)
            .use { it.body.string() }
    }

    private suspend fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = "/manga/$urlId"
        title = this@toSManga.title

        thumbnail_url = cover?.image?.link?.let { link ->
            if (link.startsWith("s3:")) presignS3Key(link.substringAfter("s3:")) else link
        }

        description = this@toSManga.description

        status = when (this@toSManga.status?.uppercase()) {
            "ON_GOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "CANCELLED" -> SManga.CANCELLED
            "HIATUS" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        genre = (listOfNotNull(type) + (genres?.map { it.name } ?: emptyList())).joinToString()
        author = authors?.joinToString { it.name }?.ifBlank { null }
        artist = artists?.joinToString { it.name }?.ifBlank { this@apply.author }

        val extraInfo = StringBuilder()
        if (!teams.isNullOrEmpty()) {
            extraInfo.append("\n\nTeams: ").append(teams.joinToString { it.name })
        }
        val year = this@toSManga.publishDate?.substringBefore("-")
        if (!year.isNullOrBlank()) {
            extraInfo.append("\nAnnée: ").append(year)
        }
        if (extraInfo.isNotEmpty()) {
            description = (description ?: "") + extraInfo.toString()
        }
    }

    private fun RscChapterDto.toSChapter(mangaUuid: String): SChapter = SChapter.create().apply {
        url = "/manga/$mangaUuid/chapter/$id"
        name = "Chapitre $orderIdString"
        chapter_number = orderId
        date_upload = DATE_FORMAT.tryParse(publishDate?.take(19))
        scanlator = "Astral Manga"
    }

    // ========================== Pages ==========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val images = document.extractNextJs<List<RscImageDto>>()
        if (images != null) {
            return images.sortedBy { it.orderId }.mapIndexed { index, img ->
                val imageUrl = if (img.link.startsWith("s3:")) {
                    presignS3Key(img.link.substringAfter("s3:")) ?: ""
                } else {
                    img.link
                }
                Page(index, imageUrl = imageUrl)
            }
        }

        return document.select("img[alt~=^Page \\d+]").mapIndexed { index, img ->
            val imageUrl = img.absUrl("src").ifEmpty {
                val src = img.attr("src")
                if (src.startsWith("http")) src else "$baseUrl$src"
            }
            Page(index, imageUrl = imageUrl)
        }
    }

    // ========================== Presign ==========================

    private suspend fun presignS3Key(s3Key: String): String? = try {
        val url = "$baseUrl/api/s3/presign-get".toHttpUrl().newBuilder()
            .addQueryParameter("key", s3Key)
            .build()
        client.get(url).parseAs<PresignResponseDto>().url
    } catch (_: Exception) {
        null
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

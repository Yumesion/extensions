package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Aniverse : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ========================== Popular / Latest ==========================

    override fun popularMangaRequest(page: Int): Request = listRequest(page)

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page)

    private fun listRequest(page: Int): Request = GET("$baseUrl/api/manga?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = listParse(response)

    override fun latestUpdatesParse(response: Response): MangasPage = listParse(response)

    private fun listParse(response: Response): MangasPage {
        val dto = response.parseAs<MangaListDto>()
        val mangas = dto.items.map { it.toSManga() }
        return MangasPage(mangas, dto.hasNextPage)
    }

    // ========================== Search ==========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = buildJsonObject {
            put("query", ANILIST_QUERY)
            put("variables", buildJsonObject { put("s", query) })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        return POST(ANILIST_URL, headers.newBuilder().set("Content-Type", "application/json").build(), body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<AniListSearchDto>()
        val mangas = dto.data?.Page?.media.orEmpty().mapNotNull { media ->
            val mangaTitle = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            SManga.create().apply {
                title = mangaTitle
                url = "/manga/${slugify(mangaTitle)}"
            }
        }
        return MangasPage(mangas, false)
    }

    // ========================== Details ==========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val urlId = response.request.url.pathSegments.last()
        val manga = response.extractNextJs<MangaDto> {
            it is JsonObject && (
                it["slug"]?.jsonPrimitive?.contentOrNull == urlId ||
                    it["databaseId"]?.jsonPrimitive?.contentOrNull == urlId
                )
        } ?: throw Exception("Manga introuvable sur Aniverse")
        return manga.toSManga()
    }

    // ========================== Chapters ==========================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.last()
        val chapters = response.extractNextJs<List<ChapterDto>> {
            it is JsonArray && it.firstOrNull()?.let { e -> e is JsonObject && e.containsKey("previewImage") } == true
        } ?: return emptyList()

        return chapters.map { ch ->
            SChapter.create().apply {
                url = "/read/$slug/${ch.id}"
                name = if (ch.premiumUntil != null) "🔒 ${ch.title}" else ch.title
                chapter_number = ch.number.toFloatOrNull() ?: 0f
                date_upload = DATE_FORMAT.tryParse(ch.updatedAt?.take(19))
            }
        }
    }

    // ========================== Pages ==========================

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val raw = response.body.string()
        val images = RAW_IMAGE_REGEX.findAll(raw).map { it.value }.distinct().toList()
        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used.")

    // ========================== Helpers ==========================

    private fun slugify(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    companion object {
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val ANILIST_QUERY =
            "query(\$s:String){Page(perPage:25){media(search:\$s,type:MANGA){title{romaji english}}}}"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val RAW_IMAGE_REGEX = Regex("""https://cdn\.onefy\.me/raw/[^"'\\\s]+""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

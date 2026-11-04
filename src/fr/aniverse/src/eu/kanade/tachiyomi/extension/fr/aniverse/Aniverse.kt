package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
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
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Aniverse : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("Referer", "$baseUrl/").build())
        }
    }

    // ========================== Popular / Latest ==========================

    override suspend fun getPopularManga(page: Int): MangasPage = mangaList(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaList(page)

    private suspend fun mangaList(page: Int): MangasPage {
        val dto = client.get("$baseUrl/api/manga?page=$page").parseAs<MangaListDto>()
        return MangasPage(dto.items.map { it.toSManga() }, dto.hasNextPage)
    }

    // ========================== Search ==========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val body = buildJsonObject {
            put("query", ANILIST_QUERY)
            put("variables", buildJsonObject { put("s", query) })
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val dto = client.post(ANILIST_URL, body).parseAs<AniListSearchDto>()
        val mangas = dto.data?.Page?.media.orEmpty().mapNotNull { media ->
            val mangaTitle = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            SManga.create().apply {
                title = mangaTitle
                url = "/manga/${slugify(mangaTitle)}"
            }
        }
        return MangasPage(mangas, false)
    }

    // ========================== Details + Chapters ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val urlId = manga.url.substringAfterLast("/")

        val updatedManga = if (fetchDetails) {
            document.extractNextJs<MangaDto> {
                it is JsonObject && (
                    it["slug"]?.jsonPrimitive?.contentOrNull == urlId ||
                        it["databaseId"]?.jsonPrimitive?.contentOrNull == urlId
                    )
            }?.toSManga() ?: manga
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            document.extractNextJs<List<ChapterDto>> {
                it is JsonArray && it.firstOrNull()?.let { e -> e is JsonObject && e.containsKey("previewImage") } == true
            }.orEmpty().map { ch ->
                SChapter.create().apply {
                    url = "/read/$urlId/${ch.id}"
                    name = if (ch.premiumUntil != null) "🔒 ${ch.title}" else ch.title
                    chapter_number = ch.number.toFloatOrNull() ?: 0f
                    date_upload = DATE_FORMAT.tryParse(ch.updatedAt?.take(19))
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ========================== Pages ==========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val raw = client.get(baseUrl + chapter.url).use { it.body.string() }
        val images = RAW_IMAGE_REGEX.findAll(raw).map { it.value }.distinct().toList()
        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    // ========================== Helpers ==========================

    private fun slugify(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    companion object {
        private const val ANILIST_URL = "https://graphql.anilist.co"
        private const val ANILIST_QUERY =
            """query(${'$'}s:String){Page(perPage:25){media(search:${'$'}s,type:MANGA){title{romaji english}}}}"""

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val RAW_IMAGE_REGEX = Regex("""https://cdn\.onefy\.me/raw/[^"'\\\s]+""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

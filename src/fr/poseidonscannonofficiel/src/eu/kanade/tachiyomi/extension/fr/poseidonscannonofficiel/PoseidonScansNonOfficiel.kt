package eu.kanade.tachiyomi.extension.fr.poseidonscannonofficiel

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class PoseidonScansNonOfficiel :
    KeiSource(),
    ConfigurableSource {

    private val preferences = getPreferences()

    private val rscHeaders get() = headersBuilder().add("RSC", "1").build()

    private fun String.toAbsoluteUrl(): String = if (this.startsWith("http")) this else baseUrl + this

    private fun String.toApiCoverUrl(): String {
        if (this.startsWith("http")) return this
        if (this.contains("storage/covers/")) return "$baseUrl/api/covers/${this.substringAfter("storage/covers/")}"
        if (this.startsWith("/api/covers/")) return baseUrl + this
        if (this.startsWith("/")) return baseUrl + this
        return "$baseUrl/api/covers/$this"
    }

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val apiResponse = client.get("$baseUrl/api/manga/lastchapters?limit=16&page=$page").parseAs<LatestApiResponse>()
        val mangas = apiResponse.data.map { apiManga ->
            SManga.create().apply {
                title = apiManga.title
                url = "/serie/${apiManga.slug}"
                thumbnail_url = apiManga.slug.toApiCoverUrl() + ".webp"
            }
        }
        return MangasPage(mangas, hasNextPage = mangas.size == 16)
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangaDtos = client.get(baseUrl, rscHeaders).extractNextJs<List<PopularMangaData>>()
            ?: throw Exception("Cant scape data from Next.js")
        val mangas = mangaDtos.map { mangaDto ->
            SManga.create().apply {
                title = mangaDto.manga.title
                url = "/serie/${mangaDto.manga.slug}"
                thumbnail_url = mangaDto.manga.slug.toApiCoverUrl() + ".webp"
            }
        }
        return MangasPage(mangas, false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            if (query.isNotBlank()) addQueryParameter("search", query)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("div.grid a.block.group").map { element ->
            val url = element.attr("href")
            val title = element.selectFirst("h2")?.text()!!

            val thumbnailUrlPath = element.selectFirst("img[alt]")?.attr("srcset")?.substringBefore(" ")?.let {
                URLDecoder.decode(it, "UTF-8").substringAfter("url=").substringBefore("&")
            }

            SManga.create().apply {
                this.setUrlWithoutDomain(url)
                this.title = title
                this.thumbnail_url = thumbnailUrlPath?.takeIf { it.isNotBlank() }?.toApiCoverUrl()
            }
        }

        val hasNextPage = document.select("nav[aria-label=Pagination] a:contains(Suivant)").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Details + Chapters ===========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val pageDto = fetchMangaPage(manga.url)
        return SMangaUpdate(
            manga = if (fetchDetails) pageDto.manga.toSManga() else manga,
            chapters = if (fetchChapters) pageDto.toChapters() else chapters,
        )
    }

    private suspend fun fetchMangaPage(url: String): MangaPageDetailsData {
        // RSC data can be partial on first load; retry with cache-busting
        val first = client.get(baseUrl + url, rscHeaders).extractNextJs<MangaPageDetailsData>()
        if (first?.manga?.chapters?.isNotEmpty() == true) return first

        val retryUrl = (baseUrl + url).toHttpUrl().newBuilder()
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .build()
        return client.get(retryUrl, rscHeaders).extractNextJs<MangaPageDetailsData>()
            ?: throw Exception("Cant scape data from Next.js")
    }

    private fun MangaDetailsData.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        thumbnail_url = "$baseUrl/api/covers/${this@toSManga.slug}.webp"
        author = this@toSManga.author
        artist = this@toSManga.artist
        genre = this@toSManga.categories.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }.joinToString(", ") {
            it.replaceFirstChar { char -> char.titlecase(Locale.FRENCH) }
        }
        status = parseStatus(this@toSManga.status)
        description = this@toSManga.description?.trim()?.takeIf { it.isNotEmpty() }
        setUrlWithoutDomain("/serie/${this@toSManga.slug}")
    }

    private fun MangaPageDetailsData.toChapters(): List<SChapter> {
        val showPremium = preferences.getBoolean(SHOW_PREMIUM_KEY, SHOW_PREMIUM_DEFAULT)
        return manga.chapters.mapNotNull { ch ->
            val isLocked = ch.isPremium == true &&
                isPremiumUser != true &&
                (ch.premiumUntil?.time ?: 0L) > System.currentTimeMillis()

            if (isLocked && !showPremium) return@mapNotNull null

            SChapter.create().apply {
                val chapterNumberString = ch.number.toString().removeSuffix(".0")
                val isVolume = ch.isVolume == true || (ch.number % 1 == 0f && ch.title?.contains("volume", ignoreCase = true) == true)

                val baseName = if (isVolume) {
                    "Volume $chapterNumberString"
                } else {
                    "Chapitre $chapterNumberString"
                }
                val title = ch.title?.trim()?.takeIf { it.isNotBlank() }

                name = buildString {
                    if (isLocked) append("🔒 ")

                    append(
                        if (title != null) {
                            "$baseName - $title"
                        } else {
                            baseName
                        },
                    )

                    if (isLocked) {
                        val dateParts = formatTimestamp(
                            ch.premiumUntil?.time ?: 0L,
                        ).split(" ")
                        // formatTimestamp gives: [dd, MMMM, HH:mm]
                        append(
                            " - Free the ${dateParts.take(2).joinToString(" ")} at ${dateParts.getOrNull(2) ?: ""}",
                        )
                    }
                }.trim()
                setUrlWithoutDomain(
                    "/serie/${manga.slug}/chapter/$chapterNumberString",
                )
                date_upload = ch.createdAt.time
                chapter_number = ch.number
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase(Locale.FRENCH)) {
        "en cours" -> SManga.ONGOING
        "terminé" -> SManga.COMPLETED
        "en pause", "hiatus" -> SManga.ON_HIATUS
        "annulé", "abandonné" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageDataDto = client.get(baseUrl + chapter.url, rscHeaders).extractNextJs<PageData>()
            ?: throw Exception("Cant scape data from Next.js")
        // `isPremium` reste vrai même après l'expiration de la période premium : on ne bloque
        // que si le chapitre est encore réellement verrouillé (premiumUntil dans le futur).
        val isStillPremium = pageDataDto.currentChapter.isPremium &&
            (pageDataDto.currentChapter.premiumUntil?.time ?: 0L) > System.currentTimeMillis()
        if (isStillPremium) {
            if (pageDataDto.sessionStatus == "unauthenticated") {
                throw Exception("This chapter is premium. Please connect via the WebView to view.")
            }
            if (!pageDataDto.isPremiumUser) {
                throw Exception("This chapter is premium. You are not a premium user.")
            }
        }
        return pageDataDto.initialData.images.map { pageDto ->
            Page(
                index = pageDto.order,
                imageUrl = pageDto.originalUrl.toAbsoluteUrl(),
            )
        }.sortedBy { it.index }
    }

    // ========================== Preference =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_PREMIUM_KEY
            title = "Show premium chapters"
            summary = "Show paid chapters (identified by 🔒) in the list."
            setDefaultValue(SHOW_PREMIUM_DEFAULT)
        }.also(screen::addPreference)
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return ""
        return DateTimeFormatter.ofPattern("dd MMMM HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }

    companion object {
        private const val SHOW_PREMIUM_KEY = "show_premium_chapters"
        private const val SHOW_PREMIUM_DEFAULT = false
    }
}

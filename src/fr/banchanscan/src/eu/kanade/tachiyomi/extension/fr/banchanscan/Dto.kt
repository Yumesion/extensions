package eu.kanade.tachiyomi.extension.fr.banchanscan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for Banchan Scan.
 *
 * Le site est une app Vinext/React Server Components, mais ses données sont servies
 * par une API REST propre (proxy Supabase) : `GET /api/data/<table>?...`.
 * On parle donc directement à cette API au lieu de rendre le HTML côté client.
 */

@Serializable
class Webtoon(
    val id: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("alt_title") val altTitle: String? = null,
    val year: String? = null,
    val type: String? = null,
    val status: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    @SerialName("is_adult") val isAdult: Boolean = false,
    val author: String? = null,
    val artist: String? = null,
    val demographic: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("alternative_titles") val alternativeTitles: List<String> = emptyList(),
)

@Serializable
class Chapter(
    val id: String,
    @SerialName("webtoon_id") val webtoonId: String,
    @SerialName("chapter_number") val chapterNumber: String,
    val season: String? = null,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val status: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("uploader_name") val uploaderName: String? = null,
)

@Serializable
class ChapterPage(
    @SerialName("chapter_id") val chapterId: String,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
class HomeData(
    val webtoons: List<Webtoon> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
)

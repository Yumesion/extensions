package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class MangaListDto(
    val items: List<MangaListItemDto> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
)

@Serializable
class MangaListItemDto(
    val id: String,
    val slug: String? = null,
    val title: String,
    val image: String? = null,
    val type: String? = null,
    val status: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = "/manga/${slug ?: id}"
        title = this@MangaListItemDto.title
        thumbnail_url = image
    }
}

@Serializable
class MangaDto(
    val databaseId: String,
    val slug: String? = null,
    val title: TitleDto? = null,
    val description: String? = null,
    val status: String? = null,
    val coverImage: CoverDto? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val isAdult: Boolean? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        title = this@MangaDto.title?.userPreferred
            ?: this@MangaDto.title?.english
            ?: this@MangaDto.title?.romaji
            ?: slug.orEmpty()

        thumbnail_url = coverImage?.large

        description = this@MangaDto.description

        status = when (this@MangaDto.status?.uppercase()) {
            "RELEASING" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        author = authors.joinToString().ifBlank { null }
        artist = artists.joinToString().ifBlank { this@apply.author }
        genre = genres.joinToString()
    }
}

@Serializable
class TitleDto(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
)

@Serializable
class CoverDto(
    val large: String? = null,
)

@Serializable
class ChapterDto(
    val id: String,
    val number: String,
    val title: String,
    val updatedAt: String? = null,
    val previewImage: String? = null,
    val premiumUntil: String? = null,
)

// ============================= AniList (recherche) =============================

@Serializable
class AniListSearchDto(
    val data: AniListDataDto? = null,
)

@Serializable
class AniListDataDto(
    val Page: AniListPageDto? = null,
)

@Serializable
class AniListPageDto(
    val media: List<AniListMediaDto> = emptyList(),
)

@Serializable
class AniListMediaDto(
    val title: AniListTitleDto? = null,
)

@Serializable
class AniListTitleDto(
    val romaji: String? = null,
    val english: String? = null,
)

package eu.kanade.tachiyomi.extension.fr.astralmanganonofficiel

import kotlinx.serialization.Serializable

@Serializable
class PresignResponseDto(
    val url: String,
)

@Serializable
class MangaResponseDto(
    val mangas: List<MangaDto>,
    val total: Int,
)

@Serializable
class MangaDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val urlId: String,
    val cover: CoverDto? = null,
    val status: String? = null,
    val type: String? = null,
    val publishDate: String? = null,
    val genres: List<NameDto>? = null,
    val authors: List<NameDto>? = null,
    val artists: List<NameDto>? = null,
    val teams: List<NameDto>? = null,
)

@Serializable
class CoverDto(
    val image: ImageDto? = null,
)

@Serializable
class ImageDto(
    val link: String,
)

@Serializable
class NameDto(val name: String)

@Serializable
class RscChapterDto(
    val id: String,
    val orderId: Float,
    val publishDate: String? = null,
    val mangaId: String,
) {
    val orderIdString: String
        get() = if (orderId % 1 == 0f) orderId.toInt().toString() else orderId.toString()
}

@Serializable
class RscImageDto(
    val link: String,
    val orderId: Int,
)

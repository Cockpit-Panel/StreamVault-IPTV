package com.streamvault.domain.model

sealed interface VodCatalogItem {
    val stableId: String
    val title: String
    val categoryId: Long?
    val sortTimestamp: Long

    data class MovieItem(val movie: Movie) : VodCatalogItem {
        override val stableId: String = "movie:${movie.id}"
        override val title: String = movie.name
        override val categoryId: Long? = movie.categoryId
        override val sortTimestamp: Long = movie.addedAt
    }

    data class SeriesItem(val series: Series) : VodCatalogItem {
        override val stableId: String = "series:${series.id}"
        override val title: String = series.name
        override val categoryId: Long? = series.categoryId
        override val sortTimestamp: Long = series.lastModified
    }
}

data class VodCatalogEntry(
    val categoryId: Long,
    val rawItemId: String,
    val itemType: ContentType,
    val targetId: Long,
    val rawPage: Int,
    val rawIndex: Int
)

data class VodCategoryHydration(
    val lastSuccessfulPage: Int = 0,
    val totalPages: Int = 0,
    val advertisedTotalItems: Int? = null,
    val advertisedTotalPages: Int? = null,
    val pageSize: Int = 0,
    val itemCount: Int = 0,
    val isComplete: Boolean = false,
    val isTruncated: Boolean = false,
    val hasMovies: Boolean = false,
    val hasSeries: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val hasMoreRemote: Boolean get() = !isComplete
    val isInitialLoading: Boolean get() = isLoading && itemCount == 0
    val isAppending: Boolean get() = isLoading && itemCount > 0
}

enum class VodCategoryLoadMode(val storageValue: String) {
    PAGED("paged"),
    COMPLETE_ON_OPEN("complete_on_open");

    companion object {
        fun fromStorage(value: String?): VodCategoryLoadMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: PAGED
    }
}

enum class VodCategoryHydrationRequest {
    OPEN,
    NEXT_PAGE,
    COMPLETE
}

enum class VodCategoryKind {
    UNKNOWN,
    MOVIES,
    SERIES,
    MIXED;

    companion object {
        fun from(items: List<VodCatalogItem>): VodCategoryKind {
            val hasMovies = items.any { it is VodCatalogItem.MovieItem }
            val hasSeries = items.any { it is VodCatalogItem.SeriesItem }
            return when {
                hasMovies && hasSeries -> MIXED
                hasMovies -> MOVIES
                hasSeries -> SERIES
                else -> UNKNOWN
            }
        }
    }
}

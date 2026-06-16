package com.streamvault.data.util

import com.streamvault.data.local.entity.MovieBrowseEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.domain.model.Movie
import java.util.Locale

private val YEAR_SUFFIX_REGEX = Regex("""\s*\((19|20)\d{2}\)\s*$""")
private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")

fun Movie.movieVariantGroupKey(): String = normalizedMovieVariantKey(name)

fun MovieEntity.movieVariantGroupKey(): String = normalizedMovieVariantKey(name)

fun MovieBrowseEntity.movieVariantGroupKey(): String = normalizedMovieVariantKey(name)

private fun normalizedMovieVariantKey(value: String): String {
    val withoutProviderPrefix = value.substringAfter(" - ", value)
    val withoutYearSuffix = YEAR_SUFFIX_REGEX.replace(withoutProviderPrefix, "")
    return NON_ALPHANUMERIC_REGEX.replace(withoutYearSuffix.lowercase(Locale.ROOT), "")
}

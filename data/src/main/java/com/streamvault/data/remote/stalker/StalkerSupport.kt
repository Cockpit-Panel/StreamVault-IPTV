package com.streamvault.data.remote.stalker

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.domain.model.ContentType
import java.security.MessageDigest
import java.util.Locale

internal fun stalkerSyntheticId(providerId: Long, type: ContentType, seed: String): Long {
    return stalkerStableHashId(providerId, type, seed)
}

internal fun stalkerStableHashId(providerId: Long, type: ContentType, seed: String): Long {
    val normalized = "$providerId/${type.name}/${seed.trim().lowercase(Locale.ROOT)}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    var value = 0L
    repeat(Long.SIZE_BYTES) { index ->
        value = (value shl 8) or (digest[index].toLong() and 0xffL)
    }
    return (value and Long.MAX_VALUE).coerceAtLeast(1L)
}

internal fun StalkerCategoryRecord.toCategoryEntity(
    providerId: Long,
    type: ContentType
): CategoryEntity {
    val syntheticId = stalkerSyntheticId(providerId, type, id.ifBlank { name })
    return CategoryEntity(
        providerId = providerId,
        categoryId = syntheticId,
        name = name,
        type = type,
        isAdult = com.streamvault.data.util.AdultContentClassifier.isAdultCategoryName(name)
    )
}

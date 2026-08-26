package com.streamvault.domain.model

import java.io.Serializable

data class SeriesDetailPresentationHint(
    val providerId: Long,
    val logicalGroupId: String?,
    val variants: List<VodSeriesVariant>,
    val duplicateConfidence: VodDuplicateConfidence
) : Serializable {
    init {
        require(providerId >= 0L) { "providerId must be non-negative" }
    }
}
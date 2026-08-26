package com.streamvault.domain.model

enum class VodVariantPreferenceMode(val storageValue: String) {
    BALANCED("balanced"),
    FORCE_LATEST("force_latest"),
    BEST_QUALITY("best_quality"),
    LATEST_BEST_QUALITY("latest_best_quality"),
    MOST_RELIABLE("most_reliable"),
    MANUAL_LAST_CHOICE("manual_last_choice");

    companion object {
        fun fromStorage(value: String?): VodVariantPreferenceMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: BALANCED
    }
}

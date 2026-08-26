package com.streamvault.domain.model

data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val codecV: String? = null,
    val codecA: String? = null,
    val pixelWidthHeightRatio: Float = 1f,
    val isHdr: Boolean = false
) {
    init {
        require(width >= 0) { "width must be non-negative" }
        require(height >= 0) { "height must be non-negative" }
        require(frameRate >= 0f) { "frameRate must be non-negative" }
        require(bitrate >= 0) { "bitrate must be non-negative" }
        require(pixelWidthHeightRatio > 0f) { "pixelWidthHeightRatio must be positive" }
    }

    val resolutionLabel: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height > 0 -> "${height}p"
            else -> "Unknown"
        }
    
    val isEmpty: Boolean get() = width == 0 && height == 0
}

package com.streamvault.domain.model

data class Category(
    val id: Long,
    val roomId: Long = id,
    val name: String,
    val parentId: Long? = null,
    val type: ContentType = ContentType.LIVE,
    val isVirtual: Boolean = false,
    val count: Int = 0,
    val providerOrder: Int = 0,
    val isAdult: Boolean = false,
    val isUserProtected: Boolean = false
) {
    init {
        require(count >= 0) { "count must be non-negative" }
    }
}

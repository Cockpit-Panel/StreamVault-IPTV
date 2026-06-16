package com.streamvault.data.util

import java.util.concurrent.ConcurrentHashMap

object PortalNameResolver {
    private val cache = ConcurrentHashMap<String, String>()

    fun register(url: String, name: String) {
        val cleanUrl = normalizeUrl(url)
        if (cleanUrl.isNotEmpty() && name.isNotEmpty()) {
            cache[cleanUrl] = name
        }
    }

    fun resolve(url: String): String? {
        val cleanUrl = normalizeUrl(url)
        return cache[cleanUrl]
    }

    private fun normalizeUrl(url: String): String {
        var clean = url.trim().lowercase()
        if (clean.startsWith("http://")) clean = clean.substring(7)
        if (clean.startsWith("https://")) clean = clean.substring(8)
        if (clean.startsWith("www.")) clean = clean.substring(4)
        if (clean.endsWith("/")) clean = clean.substring(0, clean.length - 1)
        return clean
    }
}

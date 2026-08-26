package com.streamvault.domain.manager

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode

data class ValidatedXtreamProviderInput(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String
)

data class ValidatedM3uProviderInput(
    val url: String,
    val name: String,
    val httpUserAgent: String,
    val httpHeaders: String
)

data class ValidatedStalkerProviderInput(
    val portalUrl: String,
    val macAddress: String,
    val name: String,
    val authMode: StalkerAuthMode,
    val username: String,
    val password: String,
    val httpUserAgent: String,
    val httpHeaders: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String,
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val stalkerAdvancedOptionsJson: String = ""
)

data class ValidatedJellyfinProviderInput(
    val serverUrl: String,
    val username: String,
    val password: String,
    val name: String
)

data class ValidatedJellyfinQuickConnectProviderInput(
    val serverUrl: String,
    val name: String
)

interface ProviderSetupInputValidator {
    fun validateXtream(
        serverUrl: String,
        username: String,
        password: String,
        allowBlankPassword: Boolean = false,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = ""
    ): Result<ValidatedXtreamProviderInput>

    fun validateM3u(
        url: String,
        name: String,
        httpUserAgent: String = "",
        httpHeaders: String = ""
    ): Result<ValidatedM3uProviderInput>

    fun validateStalker(
        portalUrl: String,
        macAddress: String,
        name: String,
        authMode: StalkerAuthMode,
        username: String,
        password: String,
        allowBlankPassword: Boolean = false,
        httpUserAgent: String = "",
        httpHeaders: String = "",
        deviceProfile: String,
        timezone: String,
        locale: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        stalkerAdvancedOptionsJson: String = ""
    ): Result<ValidatedStalkerProviderInput>

    fun validateJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        name: String,
        allowBlankPassword: Boolean = false
    ): Result<ValidatedJellyfinProviderInput>

    fun validateJellyfinQuickConnect(
        serverUrl: String,
        name: String
    ): Result<ValidatedJellyfinQuickConnectProviderInput>
}

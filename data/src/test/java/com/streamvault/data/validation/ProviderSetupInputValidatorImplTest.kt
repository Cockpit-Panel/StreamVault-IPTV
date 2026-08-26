package com.streamvault.data.validation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import org.junit.Test

class ProviderSetupInputValidatorImplTest {

    private val validator = ProviderSetupInputValidatorImpl()

    @Test
    fun `validateXtream rejects blank password for new providers`() {
        val result = validator.validateXtream(
            serverUrl = "https://example.com",
            username = "alice",
            password = "",
            allowBlankPassword = false,
            name = "Premium"
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo("Please enter password")
    }

    @Test
    fun `validateXtream allows blank password when editing existing providers`() {
        val result = validator.validateXtream(
            serverUrl = "https://example.com",
            username = "alice",
            password = "",
            allowBlankPassword = true,
            name = "Premium"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data.password).isEmpty()
    }

    @Test
    fun `validateXtream rejects control characters in password`() {
        val result = validator.validateXtream(
            serverUrl = "https://example.com",
            username = "alice",
            password = "sec\u0000ret",
            allowBlankPassword = false,
            name = "Premium"
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).isEqualTo("Password cannot contain control characters.")
    }

    // ── Stalker semantic validation ──────────────────────────────────────────

    private fun stalkerResult(
        timezone: String = "UTC",
        locale: String = "en",
        deviceProfile: String = "MAG250",
        authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
        username: String = "",
        password: String = "",
        macAddress: String = "00:1A:79:12:34:56",
        httpHeaders: String = ""
    ) = validator.validateStalker(
        portalUrl = "https://portal.example.com",
        macAddress = macAddress,
        name = "MAG",
        authMode = authMode,
        username = username,
        password = password,
        httpUserAgent = "",
        httpHeaders = httpHeaders,
        deviceProfile = deviceProfile,
        timezone = timezone,
        locale = locale,
        serialNumber = "",
        deviceId = "",
        deviceId2 = "",
        signature = ""
    )

    @Test
    fun `validateStalker accepts blank optional fields and uses defaults`() {
        val result = stalkerResult(timezone = "", locale = "", deviceProfile = "")
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker allows blank custom header values for default-header removal`() {
        val result = stalkerResult(httpHeaders = "Referer: | X-Test: hello")
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker accepts valid timezone`() {
        assertThat(stalkerResult(timezone = "America/New_York")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(timezone = "Europe/London")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(timezone = "UTC")).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker rejects unknown timezone identifier`() {
        val result = stalkerResult(timezone = "Not/ATimezone")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("recognized")
    }

    @Test
    fun `validateStalker rejects timezone with semicolon that would break cookie header`() {
        // Cookie header: "timezone=<value>" — a semicolon splits into a new cookie pair.
        val result = stalkerResult(timezone = "UTC;injected=value")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("characters that are not allowed")
    }

    @Test
    fun `validateStalker rejects timezone with comma that would break cookie header`() {
        val result = stalkerResult(timezone = "UTC,extra")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("characters that are not allowed")
    }

    @Test
    fun `validateStalker accepts valid BCP-47 locale`() {
        assertThat(stalkerResult(locale = "en")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(locale = "en-US")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(locale = "fr")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(locale = "zh-Hans")).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker rejects locale with semicolon that would break cookie header`() {
        val result = stalkerResult(locale = "en;injected=value")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("language tag")
    }

    @Test
    fun `validateStalker rejects locale that does not match BCP-47 pattern`() {
        val result = stalkerResult(locale = "not a locale")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("language tag")
    }

    @Test
    fun `validateStalker accepts valid device profile tokens`() {
        assertThat(stalkerResult(deviceProfile = "MAG250")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(deviceProfile = "MAG254")).isInstanceOf(Result.Success::class.java)
        assertThat(stalkerResult(deviceProfile = "Model.X-1")).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker rejects device profile with semicolon that would break cookie header`() {
        val result = stalkerResult(deviceProfile = "MAG250;injected=evil")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("Device profile must contain only")
    }

    @Test
    fun `validateStalker rejects device profile with spaces`() {
        val result = stalkerResult(deviceProfile = "MAG 250")
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("Device profile must contain only")
    }

    @Test
    fun `validateStalker auto mode accepts credentials without mac`() {
        val result = stalkerResult(
            authMode = StalkerAuthMode.AUTO,
            username = "portalUser",
            password = "portalPass",
            macAddress = ""
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `validateStalker credentials only requires username and password`() {
        val success = stalkerResult(
            authMode = StalkerAuthMode.CREDENTIALS_ONLY,
            username = "portalUser",
            password = "portalPass",
            macAddress = ""
        )
        assertThat(success).isInstanceOf(Result.Success::class.java)

        val failure = stalkerResult(
            authMode = StalkerAuthMode.CREDENTIALS_ONLY,
            username = "portalUser",
            password = "",
            macAddress = ""
        )
        assertThat(failure).isInstanceOf(Result.Error::class.java)
    }

    @Test
    fun `validateStalker mac plus credentials requires both identities`() {
        val result = stalkerResult(
            authMode = StalkerAuthMode.MAC_PLUS_CREDENTIALS,
            username = "portalUser",
            password = "portalPass",
            macAddress = "00:1A:79:12:34:56"
        )
        assertThat(result).isInstanceOf(Result.Success::class.java)

        val missingMac = stalkerResult(
            authMode = StalkerAuthMode.MAC_PLUS_CREDENTIALS,
            username = "portalUser",
            password = "portalPass",
            macAddress = ""
        )
        assertThat(missingMac).isInstanceOf(Result.Error::class.java)
    }
}

package com.streamvault.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.streamvault.app.BuildConfig
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Locale

enum class OfficialBuildStatus {
    OFFICIAL,
    UNOFFICIAL,
    VERIFICATION_UNAVAILABLE
}

data class OfficialBuildVerification(
    val status: OfficialBuildStatus,
    val packageMatchesOfficialId: Boolean,
    val signingCertSha256: String?
)

object OfficialBuildVerifier {
    fun verify(context: Context): OfficialBuildVerification {
        val expectedPackageName = BuildConfig.OFFICIAL_APPLICATION_ID.trim()
        val expectedFingerprint = normalizeFingerprint(BuildConfig.OFFICIAL_SIGNING_CERT_SHA256)
        val packageMatchesOfficialId = context.packageName == expectedPackageName
        val signingCertSha256 = runCatching { loadSigningCertSha256(context) }.getOrNull()

        val status = when {
            expectedPackageName.isBlank() || expectedFingerprint.isBlank() || signingCertSha256 == null ->
                OfficialBuildStatus.VERIFICATION_UNAVAILABLE
            packageMatchesOfficialId && normalizeFingerprint(signingCertSha256) == expectedFingerprint ->
                OfficialBuildStatus.OFFICIAL
            else -> OfficialBuildStatus.UNOFFICIAL
        }

        return OfficialBuildVerification(
            status = status,
            packageMatchesOfficialId = packageMatchesOfficialId,
            signingCertSha256 = signingCertSha256
        )
    }

    private fun loadSigningCertSha256(context: Context): String? {
        val packageManager = context.packageManager
        val packageInfo = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            else -> {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        }

        val signingCertificateBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
                ?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        }
            ?: return null

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signingCertificateBytes))

        return MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }
    }

    private fun normalizeFingerprint(value: String?): String {
        return value
            .orEmpty()
            .uppercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }
}

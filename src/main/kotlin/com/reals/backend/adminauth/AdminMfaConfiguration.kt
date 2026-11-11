package com.reals.backend.adminauth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.security.MessageDigest
import java.util.HexFormat
import org.apache.commons.codec.binary.Base32

@ConfigurationProperties("admin-mfa")
class AdminMfaProperties(
    val totpSecret: String = "",
    val secretVersion: String = "",
    val grantTtlSeconds: Long = 900,
    val maxFailedAttempts: Int = 5,
    val failureWindowSeconds: Long = 600,
    val lockoutSeconds: Long = 900,
    val maxAuthAgeSeconds: Long = 300
) {
    val key: ByteArray? = if (totpSecret.isBlank()) null else {
        require(Regex("[A-Z2-7]{32,128}").matches(totpSecret)) { "Invalid ADMIN_MFA_TOTP_SECRET Base32 format" }
        Base32().decode(totpSecret).also {
            require(it.size >= 20 && Base32().encodeAsString(it).trimEnd('=') == totpSecret) { "Invalid ADMIN_MFA_TOTP_SECRET encoding" }
        }
    }
    init {
        require(key == null || Regex("[A-Za-z0-9._-]{1,64}").matches(secretVersion)) { "ADMIN_MFA_SECRET_VERSION is required" }
        require(grantTtlSeconds in 1..900 && maxAuthAgeSeconds in 1..300)
        require(maxFailedAttempts in 1..20 && failureWindowSeconds in 1..86400 && lockoutSeconds in 1..86400)
    }
    // A secret change invalidates grants even if an operator forgets to change the version.
    val factorId: String? = key?.let { sha256(secretVersion + ":" + totpSecret) }
}

@ConfigurationProperties("backoffice")
class AdminIdentityProperties(
    val adminUids: String = "",
    val adminEmails: String = "",
    val allowedWebAppIds: String = ""
) {
    val uids = split(adminUids)
    val emails = split(adminEmails).map { it.lowercase(java.util.Locale.ROOT) }.toSet()
    val webAppIds = split(allowedWebAppIds)
    init {
        require(uids.size <= 1 && emails.size <= 1) { "Only one administrative identity is supported" }
        require(webAppIds.all { Regex("1:[0-9]+:web:[A-Za-z0-9]+").matches(it) }) { "Only Firebase Web App IDs are allowed" }
    }
    private fun split(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

@Configuration
@EnableConfigurationProperties(AdminMfaProperties::class, AdminIdentityProperties::class)
class AdminMfaConfiguration {
    @Bean fun adminMfaClock(): Clock = Clock.systemUTC()
}

internal fun sha256(value: String): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

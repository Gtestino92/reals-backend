package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("shared-contacts")
class SharedContactsProperties(
    val enabled: Boolean = false,
    val importEnabled: Boolean = false,
    val disclosureEnabled: Boolean = false,
    val consentVersion: Int = 1,
    val keyVersion: Int = 1,
    secret: String = "",
    val maxDistinct: Int = 2000,
    val maxRaw: Int = 4000,
    val maxInputLength: Int = 64,
    val maxBodyBytes: Int = 300000,
    val dailyReplacements: Int = 5
) {
    val key = if (enabled) {
        val bytes = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()
        require(bytes != null && bytes.size >= 32) { "Shared contacts require a dedicated Base64 secret of at least 32 bytes" }
        SecretKeySpec(bytes, "HmacSHA256")
    } else null

    init {
        require(consentVersion > 0 && keyVersion > 0)
        require(maxDistinct in 1..maxRaw && maxRaw <= 10000 && maxInputLength in 1..256)
        require(maxBodyBytes in 1..2000000 && dailyReplacements in 1..100)
    }
}

package com.reals.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("security.account-provisioning-limit")
data class AccountProvisioningLimitProperties(
    val enabled: Boolean = false,
    val dailyCapacity: Int = 3,
    val weeklyCapacity: Int = 5,
    val dailyWindowHours: Long = 24,
    val weeklyWindowDays: Long = 7,
    val hmacSecret: String = "",
    val cleanupBatchSize: Int = 500
) {
    init {
        require(dailyCapacity > 0 && weeklyCapacity >= dailyCapacity) { "Invalid provisioning capacities" }
        require(dailyWindowHours in 1..8760 && weeklyWindowDays in 1..365) { "Invalid provisioning windows" }
        require(Duration.ofDays(weeklyWindowDays) >= Duration.ofHours(dailyWindowHours)) { "Weekly window must cover daily window" }
        require(cleanupBatchSize in 1..10000) { "Invalid provisioning cleanup batch size" }
        if (enabled) secretBytes()
    }

    fun secretBytes(): ByteArray {
        val bytes = try { Base64.getDecoder().decode(hmacSecret) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Provisioning HMAC secret must be Base64")
        }
        require(bytes.size >= 32) { "Provisioning HMAC secret requires at least 32 random bytes in Base64" }
        return bytes
    }
}

@Configuration
@EnableConfigurationProperties(AccountProvisioningLimitProperties::class)
class AccountProvisioningLimitConfig

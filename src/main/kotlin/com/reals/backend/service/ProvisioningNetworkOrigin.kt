package com.reals.backend.service

import com.reals.backend.config.AccountProvisioningLimitProperties
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class ProvisioningNetworkOrigin(private val properties: AccountProvisioningLimitProperties) {
    fun identifier(remoteAddr: String): String {
        // Only numeric servlet addresses; never resolve hostnames or inspect HTTP headers.
        require(remoteAddr.isNotBlank() && remoteAddr.all { it in "0123456789abcdefABCDEF:." }) {
            "A numeric servlet client address is required"
        }
        val bytes = if (':' in remoteAddr) InetAddress.getByName(remoteAddr).address else {
            val parts = remoteAddr.split('.')
            require(parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull() in 0..255 }) {
                "Invalid IPv4 servlet address"
            }
            parts.map { it.toInt().toByte() }.toByteArray()
        }
        val origin = if (bytes.size == 4) byteArrayOf(4) + bytes else byteArrayOf(6) + bytes.copyOfRange(0, 8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.secretBytes(), "HmacSHA256"))
        return mac.doFinal(origin).joinToString("") { "%02x".format(it) }
    }

    companion object {
        // Namespaced lock identities, separate from stored network HMACs.
        fun lockKey(value: String): Long = java.nio.ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        ).long
    }
}

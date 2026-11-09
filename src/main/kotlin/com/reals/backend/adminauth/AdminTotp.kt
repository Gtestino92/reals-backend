package com.reals.backend.adminauth

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

class AdminTotp(key: ByteArray) {
    private val key = SecretKeySpec(key, "HmacSHA1")
    private val generator = TimeBasedOneTimePasswordGenerator()
    fun acceptedStep(code: String, now: Instant, lastAccepted: Long): Long? {
        if (!Regex("[0-9]{6}").matches(code)) return null
        val step = now.epochSecond / 30
        // Evaluate all candidates; prefer the highest match in the unlikely event of a collision.
        var matched: Long? = null
        for (candidate in (step - 1)..(step + 1)) {
            val expected = generator.generateOneTimePasswordString(key, Instant.ofEpochSecond(candidate * 30))
            if (MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), code.toByteArray(Charsets.US_ASCII)) && candidate > lastAccepted) matched = candidate
        }
        return matched
    }
}

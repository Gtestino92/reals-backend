package com.reals.backend.config.legal

import java.security.MessageDigest
import java.util.HexFormat

object LegalDocumentSha256 {
    fun hash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return HexFormat.of().formatHex(digest)
    }
}

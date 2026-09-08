package com.reals.backend.config.legal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LegalDocumentSha256Test {

    @Test
    fun `hashes exact bytes as lowercase SHA-256`() {
        val hash = LegalDocumentSha256.hash("abc".toByteArray(Charsets.UTF_8))

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hash
        )
    }

    @Test
    fun `hash is byte sensitive`() {
        val withoutNewline = LegalDocumentSha256.hash("same visible content".toByteArray(Charsets.UTF_8))
        val withNewline = LegalDocumentSha256.hash("same visible content\n".toByteArray(Charsets.UTF_8))

        assertNotEquals(withoutNewline, withNewline)
    }
}

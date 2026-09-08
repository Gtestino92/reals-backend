package com.reals.backend.validation

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SingleLinePlainTextTest {

    @Test
    fun `requireValid accepts normal text`() {
        assertDoesNotThrow {
            SingleLinePlainText.requireValid("Field", "Normal text 123")
        }
    }

    @Test
    fun `requireValid rejects line feed`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad\ntext")
        }
    }

    @Test
    fun `requireValid rejects carriage return`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad\rtext")
        }
    }

    @Test
    fun `requireValid rejects tab`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad\ttext")
        }
    }

    @Test
    fun `requireValid rejects line separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad\u2028text")
        }
    }

    @Test
    fun `requireValid rejects paragraph separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad\u2029text")
        }
    }

    @Test
    fun `requireValid rejects less-than`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad < text")
        }
    }

    @Test
    fun `requireValid rejects greater-than`() {
        assertThrows(IllegalArgumentException::class.java) {
            SingleLinePlainText.requireValid("Field", "Bad > text")
        }
    }
}

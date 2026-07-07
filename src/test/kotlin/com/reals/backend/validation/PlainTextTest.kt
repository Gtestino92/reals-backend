package com.reals.backend.validation

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PlainTextTest {

    @Test
    fun `requireValid accepts normal single-line text`() {
        assertDoesNotThrow {
            PlainText.requireValid("Field", "Normal text 123")
        }
    }

    @Test
    fun `requireValid accepts line feed`() {
        assertDoesNotThrow {
            PlainText.requireValid("Field", "Line one\nLine two")
        }
    }

    @Test
    fun `requireValid accepts carriage return`() {
        assertDoesNotThrow {
            PlainText.requireValid("Field", "Line one\rLine two")
        }
    }

    @Test
    fun `requireValid rejects tab`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlainText.requireValid("Field", "Bad\ttext")
        }
    }

    @Test
    fun `requireValid rejects other control character`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlainText.requireValid("Field", "Bad\u0000text")
        }
    }

    @Test
    fun `requireValid rejects less-than`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlainText.requireValid("Field", "Bad < text")
        }
    }

    @Test
    fun `requireValid rejects greater-than`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlainText.requireValid("Field", "Bad > text")
        }
    }
}

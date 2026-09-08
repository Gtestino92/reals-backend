package com.reals.backend.service

object UserEmailNormalizer {
    private val emailPattern =
        Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)

    fun normalizeRequired(email: String): String {
        val normalizedEmail = email.trim().lowercase()

        require(normalizedEmail.isNotBlank()) {
            "Email is required"
        }

        validate(normalizedEmail)

        return normalizedEmail
    }

    fun normalizeOptional(email: String?): String? {
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null

        validate(normalizedEmail)

        return normalizedEmail
    }

    private fun validate(normalizedEmail: String) {
        require(normalizedEmail.length <= 255) {
            "Email must be at most 255 characters"
        }

        require(emailPattern.matches(normalizedEmail)) {
            "Email format is invalid"
        }
    }
}


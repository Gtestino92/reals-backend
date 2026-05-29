package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository
) {

    private companion object {
        private val EMAIL_PATTERN =
            Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE)
    }

    fun findByIdOrThrow(userId: UUID): User {

        return userRepository.findById(userId)
            .orElseThrow {
                NoSuchElementException("User not found: $userId")
            }
    }

    /**
     * Creates a new user with a unique email.
     * Throws IllegalArgumentException if the email is already registered.
     */
    fun createUser(email: String): User {
        val normalizedEmail = normalizeRequiredEmail(email)

        check(!userRepository.existsByEmail(normalizedEmail)) {
            "Email already registered: $normalizedEmail"
        }

        return userRepository.save(
            User(email = normalizedEmail)
        )
    }

    /**
     * Returns the user with the given Firebase UID, creating one if it does not exist yet.
     * Called by FirebaseTokenFilter on every authenticated request.
     */
    fun findOrCreate(
        firebaseUid: String,
        email: String? = null
    ): User {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        val normalizedEmail = normalizeOptionalEmail(email)

        val existingByFirebaseUid = userRepository.findByFirebaseUid(firebaseUid)
        if (existingByFirebaseUid != null) {
            return updateFirebaseUserEmailIfNeeded(
                user = existingByFirebaseUid,
                normalizedEmail = normalizedEmail
            )
        }

        if (normalizedEmail != null) {
            val existingByEmail = userRepository.findByEmail(normalizedEmail)
            if (existingByEmail != null && existingByEmail.firebaseUid == null) {
                existingByEmail.firebaseUid = firebaseUid
                existingByEmail.updatedAt = OffsetDateTime.now()
                return userRepository.save(existingByEmail)
            }
        }

        return userRepository.save(
            User(
                firebaseUid = firebaseUid,
                email = normalizedEmail?.takeUnless { userRepository.existsByEmail(it) }
            )
        )
    }

    private fun updateFirebaseUserEmailIfNeeded(
        user: User,
        normalizedEmail: String?
    ): User {
        if (
            normalizedEmail == null ||
            user.email == normalizedEmail ||
            userRepository.existsByEmail(normalizedEmail)
        ) {
            return user
        }

        user.email = normalizedEmail
        user.updatedAt = OffsetDateTime.now()
        return userRepository.save(user)
    }

    private fun normalizeRequiredEmail(email: String): String {
        val normalizedEmail = email.trim().lowercase()

        require(normalizedEmail.isNotBlank()) {
            "Email is required"
        }

        validateEmail(normalizedEmail)

        return normalizedEmail
    }

    private fun normalizeOptionalEmail(email: String?): String? {
        val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null

        validateEmail(normalizedEmail)

        return normalizedEmail
    }

    private fun validateEmail(normalizedEmail: String) {
        require(normalizedEmail.length <= 255) {
            "Email must be at most 255 characters"
        }

        require(EMAIL_PATTERN.matches(normalizedEmail)) {
            "Email format is invalid"
        }
    }
}

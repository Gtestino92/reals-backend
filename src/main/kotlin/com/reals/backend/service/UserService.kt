package com.reals.backend.service

import com.reals.backend.domain.User
import com.reals.backend.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
        val normalizedEmail = email.trim().lowercase()

        require(normalizedEmail.isNotBlank()) {
            "Email is required"
        }

        require(normalizedEmail.length <= 255) {
            "Email must be at most 255 characters"
        }

        require(EMAIL_PATTERN.matches(normalizedEmail)) {
            "Email format is invalid"
        }

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
    fun findOrCreate(firebaseUid: String): User {

        return userRepository.findByFirebaseUid(firebaseUid)
            ?: userRepository.save(
                User(firebaseUid = firebaseUid)
            )
    }
}

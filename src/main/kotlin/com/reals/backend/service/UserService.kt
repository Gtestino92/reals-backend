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

        check(!userRepository.existsByEmail(email)) {
            "Email already registered: $email"
        }

        return userRepository.save(
            User(email = email.trim().lowercase())
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

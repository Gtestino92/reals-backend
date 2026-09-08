package com.reals.backend.service.localdev

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.UserRecord
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("local-firebase")
@ConditionalOnProperty(
    prefix = "local-dev.firebase",
    name = ["email-auto-verification-enabled"],
    havingValue = "true"
)
class LocalFirebaseEmailVerificationService(
    private val firebaseAuth: FirebaseAuth
) {

    fun verifyEmail(firebaseUid: String) {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        try {
            firebaseAuth.updateUser(
                UserRecord.UpdateRequest(firebaseUid)
                    .setEmailVerified(true)
            )
        } catch (ex: FirebaseAuthException) {
            throw LocalFirebaseEmailVerificationFailedException(ex)
        }
    }
}

class LocalFirebaseEmailVerificationFailedException(
    cause: Throwable
) : RuntimeException("Local Firebase email verification update failed", cause)

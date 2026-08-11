package com.reals.backend.service.identity

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Service

enum class ExternalAccountDeletionResult {
    DELETED,
    ALREADY_ABSENT,
    NOT_CONFIGURED,
}

@Service
class FirebaseExternalAccountService(
    private val environment: Environment,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Throws(FirebaseAuthException::class)
    fun revokeRefreshTokens(firebaseUid: String) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Skipping Firebase token revocation because FirebaseApp is not initialized")
            return
        }

        FirebaseAuth.getInstance().revokeRefreshTokens(firebaseUid)
    }

    fun enableAccount(firebaseUid: String) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Skipping Firebase account enable because FirebaseApp is not initialized")
            return
        }

        FirebaseAuth.getInstance().updateUser(
            com.google.firebase.auth.UserRecord.UpdateRequest(firebaseUid)
                .setDisabled(false)
        )
    }

    fun deleteAccountIfPresent(firebaseUid: String): ExternalAccountDeletionResult {
        require(firebaseUid.isNotBlank()) {
            "Firebase UID is required"
        }

        if (FirebaseApp.getApps().isEmpty()) {
            check(!environment.acceptsProfiles(Profiles.of("local-firebase", "dev", "prod"))) {
                "FirebaseApp is required to delete an external account in a Firebase-backed profile"
            }
            return ExternalAccountDeletionResult.NOT_CONFIGURED
        }

        return try {
            FirebaseAuth.getInstance().deleteUser(firebaseUid)
            ExternalAccountDeletionResult.DELETED
        } catch (exception: FirebaseAuthException) {
            if (exception.authErrorCode == AuthErrorCode.USER_NOT_FOUND) {
                ExternalAccountDeletionResult.ALREADY_ABSENT
            } else {
                throw exception
            }
        }
    }
}

package com.reals.backend.service.identity

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FirebaseExternalAccountService {

    private val log = LoggerFactory.getLogger(javaClass)

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
}

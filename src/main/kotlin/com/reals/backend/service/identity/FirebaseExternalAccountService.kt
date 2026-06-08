package com.reals.backend.service.identity

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class FirebaseExternalAccountService {

    private val log = LoggerFactory.getLogger(javaClass)

    fun disableAccount(firebaseUid: String) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.debug("Skipping Firebase account disable because FirebaseApp is not initialized")
            return
        }

        FirebaseAuth.getInstance().updateUser(
            UserRecord.UpdateRequest(firebaseUid)
                .setDisabled(true)
        )
        FirebaseAuth.getInstance().revokeRefreshTokens(firebaseUid)
    }
}

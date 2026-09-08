package com.reals.backend.config.security.currentuser

import com.reals.backend.config.security.authentication.FirebaseSignInProvider
import java.util.UUID

data class CurrentUserAuthContext(
    val userId: UUID,
    val firebaseUid: String?,
    val email: String?,
    val emailVerified: Boolean,
    val signInProvider: FirebaseSignInProvider? = null
)

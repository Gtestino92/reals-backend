package com.reals.backend.config.security.currentuser

import java.util.UUID

data class CurrentUserAuthContext(
    val userId: UUID,
    val firebaseUid: String?,
    val email: String?,
    val emailVerified: Boolean
)

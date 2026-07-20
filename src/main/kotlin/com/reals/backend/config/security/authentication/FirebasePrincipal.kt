package com.reals.backend.config.security.authentication

data class FirebasePrincipal(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean
)

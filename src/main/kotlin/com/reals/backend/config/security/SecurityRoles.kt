package com.reals.backend.config.security

object SecurityRoles {
    const val USER = "USER"
    const val FIREBASE_AUTHENTICATED = "FIREBASE_AUTHENTICATED"

    const val ROLE_USER = "ROLE_$USER"
    const val ROLE_FIREBASE_AUTHENTICATED = "ROLE_$FIREBASE_AUTHENTICATED"
}

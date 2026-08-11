package com.reals.backend.config.security.authentication

import com.google.firebase.auth.FirebaseToken

enum class FirebaseSignInProvider {
    PASSWORD,
    GOOGLE;

    companion object {
        fun fromFirebaseValue(value: String?): FirebaseSignInProvider? =
            when (value) {
                "password" -> PASSWORD
                "google.com" -> GOOGLE
                else -> null
            }

        fun fromToken(token: FirebaseToken): FirebaseSignInProvider? {
            val firebaseClaims = token.claims["firebase"] as? Map<*, *>
                ?: return null
            return fromFirebaseValue(firebaseClaims["sign_in_provider"] as? String)
        }
    }
}


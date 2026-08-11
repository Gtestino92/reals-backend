package com.reals.backend.service

import com.reals.backend.config.security.authentication.FirebaseSignInProvider
import com.reals.backend.domain.UserAuthOrigin

object AuthOriginPolicy {
    fun originFor(signInProvider: FirebaseSignInProvider): UserAuthOrigin =
        when (signInProvider) {
            FirebaseSignInProvider.PASSWORD -> UserAuthOrigin.EMAIL_PASSWORD
            FirebaseSignInProvider.GOOGLE -> UserAuthOrigin.GOOGLE
        }

    fun passwordManagementAllowed(authOrigin: UserAuthOrigin?): Boolean =
        authOrigin == UserAuthOrigin.EMAIL_PASSWORD

    fun authenticationAllowed(
        authOrigin: UserAuthOrigin?,
        signInProvider: FirebaseSignInProvider
    ): Boolean =
        when (authOrigin) {
            UserAuthOrigin.EMAIL_PASSWORD -> true
            UserAuthOrigin.GOOGLE -> signInProvider == FirebaseSignInProvider.GOOGLE
            null -> false
        }
}


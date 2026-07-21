package com.reals.backend.config.security.appcheck

fun interface FirebaseAppCheckVerifier {
    fun verify(token: String): FirebaseAppCheckVerificationResult
}

sealed interface FirebaseAppCheckVerificationResult {
    data class Valid(val appId: String) : FirebaseAppCheckVerificationResult
    data object Invalid : FirebaseAppCheckVerificationResult
    data class Unavailable(val exceptionClass: String) : FirebaseAppCheckVerificationResult
}

package com.reals.backend.service

fun interface PasswordResetEmailDeliveryService {
    fun sendPasswordResetEmail(normalizedEmail: String)
}


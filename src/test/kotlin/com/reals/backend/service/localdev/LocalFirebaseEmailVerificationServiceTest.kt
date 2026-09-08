package com.reals.backend.service.localdev

import com.google.firebase.ErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.UserRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LocalFirebaseEmailVerificationServiceTest {

    private val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
    private val service = LocalFirebaseEmailVerificationService(firebaseAuth)

    @Test
    fun `updates authenticated firebase uid as email verified`() {
        service.verifyEmail("firebase-current-user")

        val request = capturedUpdateRequests().single()
        assertEquals("firebase-current-user", uidOf(request))
        assertEquals(true, propertiesOf(request)["emailVerified"])
    }

    @Test
    fun `repeated invocation remains successful`() {
        service.verifyEmail("firebase-current-user")
        service.verifyEmail("firebase-current-user")

        val requests = capturedUpdateRequests()
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("firebase-current-user", uidOf(request))
            assertEquals(true, propertiesOf(request)["emailVerified"])
        }
    }

    @Test
    fun `already verified user update remains successful`() {
        service.verifyEmail("firebase-already-verified")

        val request = capturedUpdateRequests().single()
        assertEquals("firebase-already-verified", uidOf(request))
        assertEquals(true, propertiesOf(request)["emailVerified"])
    }

    @Test
    fun `firebase admin failure is wrapped safely`() {
        `when`(firebaseAuth.updateUser(any(UserRecord.UpdateRequest::class.java)))
            .thenThrow(
                FirebaseAuthException(
                    ErrorCode.UNKNOWN,
                    "representative Firebase failure",
                    null,
                    null,
                    null
                )
            )

        assertThrows(LocalFirebaseEmailVerificationFailedException::class.java) {
            service.verifyEmail("firebase-current-user")
        }
    }

    @Test
    fun `blank firebase uid fails before firebase admin call`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.verifyEmail(" ")
        }

        verify(firebaseAuth, times(0)).updateUser(any(UserRecord.UpdateRequest::class.java))
    }

    private fun capturedUpdateRequests(): List<UserRecord.UpdateRequest> {
        val captor = ArgumentCaptor.forClass(UserRecord.UpdateRequest::class.java)
        verify(firebaseAuth, Mockito.atLeastOnce()).updateUser(captor.capture())
        return captor.allValues
    }

    private fun uidOf(request: UserRecord.UpdateRequest): String {
        val method = request.javaClass.getDeclaredMethod("getUid")
        method.isAccessible = true
        return method.invoke(request) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun propertiesOf(request: UserRecord.UpdateRequest): Map<String, Any?> {
        val field = request.javaClass.getDeclaredField("properties")
        field.isAccessible = true
        return field.get(request) as Map<String, Any?>
    }
}

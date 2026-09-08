package com.reals.backend.service.notification.sender

import com.google.firebase.ErrorCode
import com.google.firebase.FirebaseException
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class FirebasePushNotificationSenderTest {

    @Test
    fun `unregistered Firebase messaging error marks the registration token invalid`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        Mockito.`when`(firebaseMessaging.send(anyMessage()))
            .thenThrow(firebaseMessagingException(MessagingErrorCode.UNREGISTERED))

        val result = FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "raw-unregistered-token")),
            notification = notification()
        )

        assertFalse(result.sent)
        assertEquals(listOf("raw-unregistered-token"), result.invalidTokens)
    }

    @Test
    fun `messaging level invalid argument marks the registration token invalid`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        Mockito.`when`(firebaseMessaging.send(anyMessage()))
            .thenThrow(firebaseMessagingException(MessagingErrorCode.INVALID_ARGUMENT))

        val result = FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "raw-invalid-argument-token")),
            notification = notification()
        )

        assertFalse(result.sent)
        assertEquals(listOf("raw-invalid-argument-token"), result.invalidTokens)
    }

    @Test
    fun `invalid Firebase token does not prevent another device token from succeeding`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        Mockito.`when`(firebaseMessaging.send(anyMessage())).thenAnswer { invocation ->
            val message = invocation.arguments[0] as Message
            val token = message.fieldValue<String>("token")
                ?: error("Expected token")
            if (token == "raw-invalid-token-a") {
                throw firebaseMessagingException(MessagingErrorCode.UNREGISTERED)
            }
            "message-for-$token"
        }

        val result = FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(
                PushNotificationToken(id = UUID.randomUUID(), token = "raw-invalid-token-a"),
                PushNotificationToken(id = UUID.randomUUID(), token = "raw-valid-token-b")
            ),
            notification = notification()
        )

        assertTrue(result.sent)
        assertEquals(listOf("raw-invalid-token-a"), result.invalidTokens)
        assertEquals(listOf("message-for-raw-valid-token-b"), result.providerMessageIds)
    }

    @Test
    fun `messages without Android metadata do not include Android config`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        val capturedMessages = mutableListOf<Message>()
        Mockito.`when`(firebaseMessaging.send(anyMessage())).thenAnswer { invocation ->
            capturedMessages += invocation.arguments[0] as Message
            "message-id"
        }

        FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "token-1")),
            notification = PushNotification(
                title = "Title",
                body = "Body",
                data = mapOf("type" to "TEST")
            )
        )

        assertEquals(1, capturedMessages.size)
        val firebaseNotification = capturedMessages.single().fieldValue<Notification>("notification")
        assertNotNull(firebaseNotification)
        assertNull(capturedMessages.single().fieldValue<AndroidConfig>("androidConfig"))
    }

    @Test
    fun `sender applies optional Android notification tag and ttl`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        val capturedMessages = mutableListOf<Message>()
        Mockito.`when`(firebaseMessaging.send(anyMessage())).thenAnswer { invocation ->
            capturedMessages += invocation.arguments[0] as Message
            "message-id"
        }

        FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "token-1")),
            notification = PushNotification(
                title = "Title",
                body = "Body",
                data = mapOf("type" to "TEST"),
                androidTtlMillis = 12_345,
                androidNotificationTag = "second-chat-connection-id"
            )
        )

        val androidConfig = capturedMessages.single().fieldValue<AndroidConfig>("androidConfig")
            ?: error("Expected AndroidConfig")
        val androidNotification = androidConfig.fieldValue<AndroidNotification>("notification")
            ?: error("Expected AndroidNotification")
        assertEquals("12.345000000s", androidConfig.fieldValue<String>("ttl"))
        assertEquals("second-chat-connection-id", androidNotification.fieldValue<String>("tag"))
        assertEquals("Title", androidNotification.fieldValue<String>("title"))
        assertEquals("Body", androidNotification.fieldValue<String>("body"))
    }

    @Test
    fun `client rendered messages keep data and Android metadata without notification payloads`() {
        val firebaseMessaging = Mockito.mock(FirebaseMessaging::class.java)
        val capturedMessages = mutableListOf<Message>()
        Mockito.`when`(firebaseMessaging.send(anyMessage())).thenAnswer { invocation ->
            capturedMessages += invocation.arguments[0] as Message
            "message-id"
        }

        val data = mapOf(
            "type" to "MATCH_FOUND",
            "matchId" to UUID.randomUUID().toString(),
            "expiresAt" to "2040-07-17T12:05:00Z"
        )

        FirebasePushNotificationSender(firebaseMessaging).sendToTokens(
            tokens = listOf(PushNotificationToken(id = UUID.randomUUID(), token = "token-1")),
            notification = PushNotification(
                title = "Encontramos un chat",
                body = "Tu nuevo chat ya está disponible.",
                data = data,
                androidTtlMillis = 12_345,
                includeNotificationPayload = false,
                androidPriority = PushNotificationAndroidPriority.HIGH
            )
        )

        val message = capturedMessages.single()
        assertNull(message.fieldValue<Notification>("notification"))
        assertEquals(data, message.fieldValue<Map<String, String>>("data"))
        val androidConfig = message.fieldValue<AndroidConfig>("androidConfig")
            ?: error("Expected AndroidConfig")
        assertEquals("12.345000000s", androidConfig.fieldValue<String>("ttl"))
        assertEquals("high", androidConfig.fieldValue<String>("priority"))
        assertNull(androidConfig.fieldValue<AndroidNotification>("notification"))
    }

    private fun anyMessage(): Message {
        Mockito.any(Message::class.java)
        return Message.builder()
            .setToken("placeholder-token")
            .build()
    }

    private fun notification(): PushNotification =
        PushNotification(
            title = "Title",
            body = "Body",
            data = mapOf("type" to "TEST")
        )

    private fun firebaseMessagingException(errorCode: MessagingErrorCode): FirebaseMessagingException {
        val baseException =
            FirebaseException(
                ErrorCode.INVALID_ARGUMENT,
                "Firebase messaging test error $errorCode",
                null
            )
        val factory = FirebaseMessagingException::class.java.getDeclaredMethod(
            "withMessagingErrorCode",
            FirebaseException::class.java,
            MessagingErrorCode::class.java
        )
        factory.isAccessible = true
        return factory.invoke(null, baseException, errorCode) as FirebaseMessagingException
    }

    private inline fun <reified T> Any.fieldValue(fieldName: String): T? {
        val field = javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this) as T?
    }
}

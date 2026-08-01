package com.reals.backend.service.notification.sender

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class FirebasePushNotificationSenderTest {

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

    private fun anyMessage(): Message {
        Mockito.any(Message::class.java)
        return Message.builder()
            .setToken("placeholder-token")
            .build()
    }

    private inline fun <reified T> Any.fieldValue(fieldName: String): T? {
        val field = javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this) as T?
    }
}

package com.reals.backend.service.notification.sender

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("!local-firebase & !dev & !prod")
class NoopPushNotificationSender : PushNotificationSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendToTokens(
        tokens: List<PushNotificationToken>,
        notification: PushNotification
    ): PushSendResult {
        log.debug(
            "Skipping push notification send because no provider sender is configured. tokens={}",
            tokens.size
        )

        return PushSendResult(
            sent = tokens.isNotEmpty(),
            providerMessageIds = tokens.map { "noop:${it.id}" }
        )
    }
}

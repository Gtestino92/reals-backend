package com.reals.backend.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties(prefix = "chat.audio")
data class ChatAudioProperties(
    val enabled: Boolean = false,
    val maxDurationMillis: Long = 60_000,
    val maxFileSizeBytes: Long = 2 * 1024 * 1024,
    val allowedContentTypes: List<String> = listOf("audio/mp4"),
    val upload: ChatAudioUploadProperties = ChatAudioUploadProperties()
) {
    init {
        require(maxDurationMillis > 0) { "chat.audio.max-duration-millis must be positive" }
        require(maxFileSizeBytes > 0) { "chat.audio.max-file-size-bytes must be positive" }
        require(allowedContentTypes == listOf("audio/mp4")) {
            "chat.audio.allowed-content-types must be exactly audio/mp4"
        }
    }
}

data class ChatAudioUploadProperties(
    val maxConcurrent: Int = 2,
    val permitWaitDuration: Duration = Duration.ZERO,
    val retryAfterSeconds: Long = 1
) {
    init {
        require(maxConcurrent > 0) { "chat.audio.upload.max-concurrent must be positive" }
        require(!permitWaitDuration.isNegative) { "chat.audio.upload.permit-wait-duration must be non-negative" }
        require(retryAfterSeconds > 0) { "chat.audio.upload.retry-after-seconds must be positive" }
    }
}

@ConfigurationProperties(prefix = "chat.first-chat.audio")
data class FirstChatAudioProperties(
    val maxPerUser: Int = 1,
    val requiredAnsweredGuidanceQuestions: Int = 2
) {
    init {
        require(maxPerUser > 0) { "chat.first-chat.audio.max-per-user must be positive" }
        require(requiredAnsweredGuidanceQuestions >= 0) {
            "chat.first-chat.audio.required-answered-guidance-questions must be non-negative"
        }
    }
}

@ConfigurationProperties(prefix = "chat.second-chat.audio")
data class SecondChatAudioProperties(
    val enabledAfterConversationMinutes: Long = 10
) {
    init {
        require(enabledAfterConversationMinutes >= 0) {
            "chat.second-chat.audio.enabled-after-conversation-minutes must be non-negative"
        }
    }
}

@Configuration
@EnableConfigurationProperties(
    ChatAudioProperties::class,
    FirstChatAudioProperties::class,
    SecondChatAudioProperties::class
)
class ChatAudioConfiguration

@org.springframework.stereotype.Component
class ChatAudioStartupValidator(
    private val firstChatAudioProperties: FirstChatAudioProperties,
    @org.springframework.beans.factory.annotation.Value("\${chat.first-chat.guidance.max-questions:3}")
    private val maxQuestions: Int
) : InitializingBean {
    override fun afterPropertiesSet() {
        require(firstChatAudioProperties.requiredAnsweredGuidanceQuestions < maxQuestions) {
            "chat.first-chat.audio.required-answered-guidance-questions must be less than chat.first-chat.guidance.max-questions"
        }
    }
}

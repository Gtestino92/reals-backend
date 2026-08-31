package com.reals.backend.service.identity

import com.fasterxml.jackson.annotation.JsonProperty
import com.reals.backend.service.PasswordResetEmailDeliveryService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

@Service
class FirebasePasswordResetEmailService(
    @param:Qualifier("firebaseAuthRestClient")
    private val firebaseAuthRestClient: RestClient,
    private val properties: FirebaseAuthRestProperties
) : PasswordResetEmailDeliveryService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendPasswordResetEmail(normalizedEmail: String) {
        if (properties.webApiKey.isBlank()) {
            log.warn("Firebase password reset delivery is not configured")
            return
        }

        try {
            firebaseAuthRestClient.post()
                .uri("/accounts:sendOobCode?key={key}", properties.webApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    FirebaseSendOobCodeRequest(
                        requestType = PASSWORD_RESET_REQUEST_TYPE,
                        email = normalizedEmail
                    )
                )
                .retrieve()
                .toBodilessEntity()
        } catch (ex: RestClientResponseException) {
            if (ex.responseBodyAsString.contains("EMAIL_NOT_FOUND")) {
                log.debug("Firebase password reset delivery treated absent external email as no-op")
            } else {
                log.warn(
                    "Firebase password reset delivery failed with status={} exceptionType={}",
                    ex.statusCode.value(),
                    ex.javaClass.simpleName
                )
            }
        } catch (ex: Exception) {
            log.warn(
                "Firebase password reset delivery failed with exception type {}",
                ex.javaClass.simpleName
            )
        }
    }

    private companion object {
        const val PASSWORD_RESET_REQUEST_TYPE = "PASSWORD_RESET"
    }
}

data class FirebaseSendOobCodeRequest(
    @param:JsonProperty("requestType")
    val requestType: String,
    val email: String
)

@ConfigurationProperties(prefix = "firebase.auth-rest")
data class FirebaseAuthRestProperties(
    val webApiKey: String = "",
    val baseUrl: String = "https://identitytoolkit.googleapis.com/v1",
    val connectTimeoutMs: Long = 3_000,
    val readTimeoutMs: Long = 10_000
) {
    init {
        require(baseUrl.isNotBlank()) {
            "firebase.auth-rest.base-url must not be blank"
        }
        require(connectTimeoutMs > 0) {
            "firebase.auth-rest.connect-timeout-ms must be positive"
        }
        require(readTimeoutMs > 0) {
            "firebase.auth-rest.read-timeout-ms must be positive"
        }
    }
}

@Configuration
@EnableConfigurationProperties(FirebaseAuthRestProperties::class)
class FirebaseAuthRestConfig {
    @Bean
    fun firebaseAuthRestClient(properties: FirebaseAuthRestProperties): RestClient {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
            setReadTimeout(Duration.ofMillis(properties.readTimeoutMs))
        }

        return RestClient.builder()
            .baseUrl(properties.baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }
}


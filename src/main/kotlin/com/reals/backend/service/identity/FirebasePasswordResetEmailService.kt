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
import org.springframework.web.client.ResourceAccessException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.SocketTimeoutException
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
            log.warn("event=password_reset outcome=firebase_unconfigured")
            return
        }

        try {
            val response = firebaseAuthRestClient.post()
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
            if (response.statusCode.is2xxSuccessful) {
                log.info("event=password_reset outcome=firebase_accepted http_status={}", response.statusCode.value())
            } else {
                logRejected(response.statusCode.value(), "UNKNOWN")
            }
        } catch (ex: RestClientResponseException) {
            logRejected(ex.statusCode.value(), providerErrorCode(ex.responseBodyAsByteArray))
        } catch (ex: ResourceAccessException) {
            if (ex.contains(SocketTimeoutException::class.java)) {
                log.warn("event=password_reset outcome=firebase_timeout")
            } else {
                log.warn("event=password_reset outcome=firebase_transport_failure")
            }
        }
    }

    private fun logRejected(status: Int, code: String) {
        log.warn("event=password_reset outcome=firebase_rejected http_status={} firebase_code={}", status, code)
    }

    private fun providerErrorCode(body: ByteArray): String {
        val error = try {
            errorMapper.readTree(body).path("error")
        } catch (_: JacksonException) {
            return "UNKNOWN"
        }
        val messageCode = error.path("message").asString("").substringBefore(':').trim()
        if (messageCode in safeErrorCodes) {
            return messageCode
        }
        return error.path("details").firstNotNullOfOrNull { detail ->
            detail.path("reason").asString("").takeIf { it in safeErrorCodes }
        } ?: "UNKNOWN"
    }

    private companion object {
        const val PASSWORD_RESET_REQUEST_TYPE = "PASSWORD_RESET"
        val errorMapper = JsonMapper()
        val safeErrorCodes = setOf(
            "EMAIL_NOT_FOUND", "INVALID_EMAIL", "USER_DISABLED",
            "RESET_PASSWORD_EXCEED_LIMIT", "TOO_MANY_ATTEMPTS_TRY_LATER", "QUOTA_EXCEEDED",
            "OPERATION_NOT_ALLOWED", "PASSWORD_LOGIN_DISABLED",
            "API_KEY_INVALID", "API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED",
            "API_KEY_IP_ADDRESS_BLOCKED", "SERVICE_DISABLED",
            "MISSING_RECAPTCHA_TOKEN", "INVALID_RECAPTCHA_TOKEN", "CAPTCHA_CHECK_FAILED",
            "TENANT_ID_MISMATCH", "INTERNAL"
        )
    }
}

data class FirebaseSendOobCodeRequest(
    @param:JsonProperty("requestType")
    val requestType: String,
    val email: String
) {
    // RestClient logs the request body's string representation at DEBUG.
    override fun toString(): String = "FirebaseSendOobCodeRequest"
}

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


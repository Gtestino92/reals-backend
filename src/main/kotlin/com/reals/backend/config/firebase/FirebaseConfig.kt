package com.reals.backend.config.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

@Configuration
@Profile("local-firebase", "dev", "prod")
class FirebaseConfig(

    @param:Value("\${firebase.service-account-path:}")
    private val serviceAccountPath: String,

    @param:Value("\${firebase.service-account-json:}")
    private val serviceAccountJson: String,

    @param:Value("\${firebase.service-account-base64:}")
    private val serviceAccountBase64: String
) {

    @Bean
    fun firebaseApp(): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return FirebaseApp.getInstance()
        }

        val credentials =
            when {
                serviceAccountPath.isNotBlank() ->
                    GoogleCredentials.fromStream(
                        File(serviceAccountPath).inputStream()
                    )

                serviceAccountJson.isNotBlank() ->
                    GoogleCredentials.fromStream(
                        ByteArrayInputStream(serviceAccountJson.toByteArray(Charsets.UTF_8))
                    )

                serviceAccountBase64.isNotBlank() ->
                    GoogleCredentials.fromStream(
                        ByteArrayInputStream(
                            Base64.getDecoder().decode(serviceAccountBase64)
                        )
                    )

                else ->
                    GoogleCredentials.getApplicationDefault()
            }

        return FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()
        )
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging =
        FirebaseMessaging.getInstance(firebaseApp)

    @Bean
    fun firebaseAuth(firebaseApp: FirebaseApp): FirebaseAuth =
        FirebaseAuth.getInstance(firebaseApp)
}

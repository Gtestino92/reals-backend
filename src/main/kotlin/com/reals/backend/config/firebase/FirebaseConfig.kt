package com.reals.backend.config.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.File

@Configuration
@Profile("local-firebase", "dev", "prod")
class FirebaseConfig(

    @param:Value("\${firebase.service-account-path:}")
    private val serviceAccountPath: String
) {

    @Bean
    fun firebaseApp(): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return FirebaseApp.getInstance()
        }

        val credentials =
            if (serviceAccountPath.isNotBlank()) {
                GoogleCredentials.fromStream(
                    File(serviceAccountPath).inputStream()
                )
            } else {
                GoogleCredentials.getApplicationDefault()
            }

        return FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()
        )
    }
}

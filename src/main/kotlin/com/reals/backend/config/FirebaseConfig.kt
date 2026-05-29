package com.reals.backend.config

/*
TODO(firebase):
Firebase Admin is already declared in pom.xml. To activate production auth:
1. Replace this placeholder with active configuration.
2. Provide FIREBASE_SERVICE_ACCOUNT_PATH or platform default credentials.
3. Enable FirebaseTokenFilter in SecurityConfig for dev/prod.
*/

/*
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.File

@Configuration
@Profile("dev", "prod")
class FirebaseConfig(

    @Value("\${firebase.service-account-path:}")
    private val serviceAccountPath: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firebaseApp(): FirebaseApp {

        if (FirebaseApp.getApps().isNotEmpty())
            return FirebaseApp.getInstance()

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
*/

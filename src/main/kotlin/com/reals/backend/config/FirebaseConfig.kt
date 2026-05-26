package com.reals.backend.config

/*
TODO(firebase):
Uncomment when firebase-admin:9.2.0
is available in local Maven repo.

Steps to activate:

1. Download firebase-admin-9.2.0.jar and run:

mvn install:install-file \
-Dfile=firebase-admin-9.2.0.jar \
-DgroupId=com.google.firebase \
-DartifactId=firebase-admin \
-Dversion=9.2.0 \
-Dpackaging=jar

2. Set env:
FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/key.json

3. Uncomment this file and FirebaseTokenFilter.kt

4. Add FirebaseTokenFilter to SecurityConfig
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

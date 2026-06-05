package com.reals.backend

import com.reals.backend.config.r2.ProfilePhotoStorageProperties
import com.reals.backend.config.r2.R2StorageProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@EnableConfigurationProperties(
    R2StorageProperties::class,
    ProfilePhotoStorageProperties::class
)
@SpringBootApplication(
    exclude = [UserDetailsServiceAutoConfiguration::class]
)
class RealsBackendApplication

fun main(args: Array<String>) {
    runApplication<RealsBackendApplication>(*args)
}

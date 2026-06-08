package com.reals.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

@SpringBootApplication(
    exclude = [UserDetailsServiceAutoConfiguration::class]
)
class RealsBackendApplication

fun main(args: Array<String>) {
    runApplication<RealsBackendApplication>(*args)
}

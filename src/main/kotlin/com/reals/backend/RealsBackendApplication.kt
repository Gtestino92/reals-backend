package com.reals.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(
    exclude = [UserDetailsServiceAutoConfiguration::class]
)
class RealsBackendApplication

fun main(args: Array<String>) {
    runApplication<RealsBackendApplication>(*args)
}

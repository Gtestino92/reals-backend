package com.reals.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import java.util.TimeZone

@SpringBootApplication(
    exclude = [UserDetailsServiceAutoConfiguration::class]
)
class RealsBackendApplication

fun main(args: Array<String>) {
    configureApplicationTimeZone()
    runApplication<RealsBackendApplication>(*args)
}

internal fun configureApplicationTimeZone() {
    val utc = "UTC"
    System.setProperty("user.timezone", utc)
    TimeZone.setDefault(TimeZone.getTimeZone(utc))
}

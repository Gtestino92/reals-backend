package com.reals.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class RealsBackendApplication

fun main(args: Array<String>) {
    runApplication<RealsBackendApplication>(*args)
}

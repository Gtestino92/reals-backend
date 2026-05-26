package com.reals.backend.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class PingController {

    @GetMapping("/api/ping")
    fun ping(): Map<String, String> =
        mapOf("status" to "ok")
}
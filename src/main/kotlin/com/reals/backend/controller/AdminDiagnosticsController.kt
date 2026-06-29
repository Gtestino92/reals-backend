package com.reals.backend.controller

import com.reals.backend.controller.dto.MatchmakingDiagnosticsResponse
import com.reals.backend.service.matching.MatchmakingDiagnosticsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/diagnostics")
class AdminDiagnosticsController(
    private val matchmakingDiagnosticsService: MatchmakingDiagnosticsService
) {

    @GetMapping("/matchmaking")
    fun getMatchmakingDiagnostics(): ResponseEntity<MatchmakingDiagnosticsResponse> =
        ResponseEntity.ok(
            MatchmakingDiagnosticsResponse.from(
                matchmakingDiagnosticsService.getDiagnostics()
            )
        )
}

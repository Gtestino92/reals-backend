package com.reals.backend.controller

import com.reals.backend.controller.dto.CountryReferenceResponse
import com.reals.backend.service.CountryReferenceService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reference")
class ReferenceController(
    private val countryReferenceService: CountryReferenceService
) {

    @GetMapping("/countries")
    fun getCountries(): ResponseEntity<List<CountryReferenceResponse>> =
        ResponseEntity.ok(
            countryReferenceService.getCountries()
                .map(CountryReferenceResponse::from)
        )
}

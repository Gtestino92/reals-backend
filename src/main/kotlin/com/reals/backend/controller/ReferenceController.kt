package com.reals.backend.controller

import com.reals.backend.controller.dto.CountryReferenceResponse
import com.reals.backend.controller.dto.AffinityQuestionCatalogResponse
import com.reals.backend.controller.dto.ProfileQuestionCatalogResponse
import com.reals.backend.service.affinity.AffinityQuestionCatalogProvider
import com.reals.backend.service.CountryReferenceService
import com.reals.backend.service.profilequestion.ProfileQuestionCatalogProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reference")
class ReferenceController(
    private val countryReferenceService: CountryReferenceService,
    private val affinityQuestionCatalogProvider: AffinityQuestionCatalogProvider,
    private val profileQuestionCatalogProvider: ProfileQuestionCatalogProvider
) {

    @GetMapping("/countries")
    fun getCountries(): ResponseEntity<List<CountryReferenceResponse>> =
        ResponseEntity.ok(
            countryReferenceService.getCountries()
                .map(CountryReferenceResponse::from)
        )

    @GetMapping("/affinity-questions")
    fun getAffinityQuestions(): ResponseEntity<AffinityQuestionCatalogResponse> =
        ResponseEntity.ok(
            AffinityQuestionCatalogResponse.from(
                affinityQuestionCatalogProvider.getCatalog()
            )
        )

    @GetMapping("/profile-questions")
    fun getProfileQuestions(): ResponseEntity<ProfileQuestionCatalogResponse> =
        ResponseEntity.ok(
            ProfileQuestionCatalogResponse.from(
                profileQuestionCatalogProvider.getCatalog()
            )
        )
}

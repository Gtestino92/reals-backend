package com.reals.backend.controller.dto

import com.reals.backend.service.CountryReference

data class CountryReferenceResponse(
    val code: String,
    val displayName: String
) {
    companion object {
        fun from(country: CountryReference): CountryReferenceResponse =
            CountryReferenceResponse(
                code = country.code,
                displayName = country.displayName
            )
    }
}

package com.reals.backend.repository

import com.reals.backend.domain.ProfileQuestionAnswer
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProfileQuestionAnswerRepository : JpaRepository<ProfileQuestionAnswer, UUID> {
    fun findByProfileId(profileId: UUID): List<ProfileQuestionAnswer>

    fun findByProfileIdAndSelectedPositionIsNotNull(profileId: UUID): List<ProfileQuestionAnswer>

    fun findByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): ProfileQuestionAnswer?

    fun deleteByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): Long

    fun countByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): Long
}

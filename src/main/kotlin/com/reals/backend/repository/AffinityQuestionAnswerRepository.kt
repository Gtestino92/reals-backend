package com.reals.backend.repository

import com.reals.backend.domain.AffinityQuestionAnswer
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AffinityQuestionAnswerRepository : JpaRepository<AffinityQuestionAnswer, UUID> {
    fun findByProfileId(profileId: UUID): List<AffinityQuestionAnswer>

    fun findByProfileIdIn(profileIds: Collection<UUID>): List<AffinityQuestionAnswer>

    fun findByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): AffinityQuestionAnswer?

    fun deleteByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): Long

    fun countByProfileIdAndQuestionId(
        profileId: UUID,
        questionId: String
    ): Long
}

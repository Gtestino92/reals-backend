package com.reals.backend.service

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.User

data class SafetyReportDetail(
    val report: SafetyReport,
    val reporter: User?,
    val reported: User?,
    val messages: List<ChatMessage>,
    val penalty: Penalty?
)

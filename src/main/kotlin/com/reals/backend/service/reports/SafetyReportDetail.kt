package com.reals.backend.service.reports

import com.reals.backend.domain.ChatMessage
import com.reals.backend.domain.Penalty
import com.reals.backend.domain.SafetyReport
import com.reals.backend.domain.SafetyReportEvidenceSnapshot
import com.reals.backend.domain.User

data class SafetyReportDetail(
    val report: SafetyReport,
    val reporter: User?,
    val reported: User?,
    val messages: List<ChatMessage>,
    val penalty: Penalty?,
    val evidence: SafetyReportEvidenceSnapshot?,
    val reportedUserCounters: SafetyReportUserCounters
)

data class SafetyReportUserCounters(
    val pendingReportsTotal: Long,
    val confirmedReportsTotal: Long,
    val confirmedReportsLast30Days: Long
)

package com.reals.backend.service

data class VisualReviewPersonalMessageStatus(
    val partnerPersonalMessageSubmitted: Boolean,
    val partnerPersonalMessageRead: Boolean,
    val decisionRequiresPartnerPersonalMessageRead: Boolean
)

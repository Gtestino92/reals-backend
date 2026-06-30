package com.reals.backend.repository

import com.reals.backend.domain.AuditEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuditEventRepository : JpaRepository<AuditEvent, UUID>

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    actor_user_id UUID,
    target_user_id UUID,
    request_id VARCHAR(255),
    ip_hash VARCHAR(255),
    user_agent_hash VARCHAR(255),
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_events_actor_user_id ON audit_events (actor_user_id);
CREATE INDEX idx_audit_events_target_user_id ON audit_events (target_user_id);
CREATE INDEX idx_audit_events_event_type ON audit_events (event_type);
CREATE INDEX idx_audit_events_aggregate ON audit_events (aggregate_type, aggregate_id);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at);

CREATE TABLE safety_report_evidence_snapshots (
    id UUID PRIMARY KEY,
    safety_report_id UUID NOT NULL,
    chat_id UUID,
    match_id UUID,
    connection_id UUID,
    message_count INTEGER NOT NULL,
    first_message_at TIMESTAMP WITH TIME ZONE,
    last_message_at TIMESTAMP WITH TIME ZONE,
    transcript_sha256 VARCHAR(64),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_safety_report_evidence_report UNIQUE (safety_report_id)
);

CREATE INDEX idx_safety_report_evidence_chat_id ON safety_report_evidence_snapshots (chat_id);
CREATE INDEX idx_safety_report_evidence_match_id ON safety_report_evidence_snapshots (match_id);
CREATE INDEX idx_safety_report_evidence_connection_id ON safety_report_evidence_snapshots (connection_id);

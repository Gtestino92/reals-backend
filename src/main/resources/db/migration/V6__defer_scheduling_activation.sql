ALTER TABLE connections
    ADD COLUMN scheduling_available_at TIMESTAMP WITH TIME ZONE;

UPDATE connections
SET scheduling_available_at = created_at
WHERE state = 'SCHEDULING_PHASE'
  AND scheduling_available_at IS NULL;

CREATE INDEX idx_connection_state_scheduling_available_at
    ON connections (state, scheduling_available_at);

CREATE INDEX idx_matchmaking_queue_waiting_order
    ON matchmaking_queue (entered_at, id)
    WHERE status = 'WAITING';

CREATE INDEX idx_matches_active_pair
    ON matches (user_a_id, user_b_id)
    WHERE state IN ('CHAT_ACTIVE', 'VISUAL_PHASE', 'VISUAL_APPROVED');

CREATE INDEX idx_matches_terminal_pair_state_updated
    ON matches (user_a_id, user_b_id, state, updated_at)
    WHERE state IN ('CHAT_REJECTED', 'VISUAL_REJECTED', 'EXPIRED');

CREATE INDEX idx_connections_active_pair
    ON connections (user_a_id, user_b_id)
    WHERE state <> 'CLOSED';

CREATE INDEX idx_connections_closed_pair_updated
    ON connections (user_a_id, user_b_id, updated_at)
    WHERE state = 'CLOSED';

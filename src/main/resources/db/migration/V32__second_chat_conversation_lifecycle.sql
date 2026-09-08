ALTER TABLE chats
    ADD COLUMN last_message_sender_id UUID;

ALTER TABLE chats
    ADD CONSTRAINT fk_chats_last_message_sender
        FOREIGN KEY (last_message_sender_id) REFERENCES users(id);

CREATE INDEX idx_chats_second_chat_last_message_sender
    ON chats (chat_type, status, last_message_sender_id, last_message_at)
    WHERE chat_type = 'SECOND_CHAT';

ALTER TABLE second_chat_resolution_requests
    ADD COLUMN reference_message_id UUID;

ALTER TABLE second_chat_resolution_requests
    ADD CONSTRAINT fk_second_chat_resolution_reference_message
        FOREIGN KEY (reference_message_id) REFERENCES chat_messages(id);

DROP INDEX IF EXISTS uq_second_chat_resolution_pending_partner_no_show;

CREATE UNIQUE INDEX uq_second_chat_resolution_pending_connection
    ON second_chat_resolution_requests (connection_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_second_chat_resolution_mutual_cooldown
    ON second_chat_resolution_requests (connection_id, requester_user_id, type, resolved_at DESC)
    WHERE type = 'MUTUAL_COMPLETION' AND resolved_at IS NOT NULL;

CREATE INDEX idx_second_chat_resolution_reference_message
    ON second_chat_resolution_requests (reference_message_id)
    WHERE reference_message_id IS NOT NULL;

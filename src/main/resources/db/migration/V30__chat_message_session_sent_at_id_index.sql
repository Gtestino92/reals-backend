CREATE INDEX idx_chat_messages_session_sent_at_id
    ON chat_messages (chat_session_id, sent_at, id);

ALTER TABLE chats
    ADD COLUMN read_only_until TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_chat_second_chat_read_only_until
    ON chats (chat_type, status, read_only_until);

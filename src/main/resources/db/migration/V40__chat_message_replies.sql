ALTER TABLE chat_messages
    ADD COLUMN reply_to_message_id UUID,
    ADD COLUMN reply_to_prompt_snapshot_id UUID;

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_reply_to_message
        FOREIGN KEY (reply_to_message_id)
        REFERENCES chat_messages (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_chat_messages_reply_to_prompt_snapshot
        FOREIGN KEY (reply_to_prompt_snapshot_id)
        REFERENCES conversation_prompt_snapshots (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_chat_messages_single_reply_target
        CHECK (
            reply_to_message_id IS NULL
            OR reply_to_prompt_snapshot_id IS NULL
        );

ALTER TABLE chat_messages
    DROP CONSTRAINT ck_chat_messages_text_shape;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_text_shape
        CHECK (
            (message_type = 'TEXT'
                AND content IS NOT NULL
                AND audio_bucket IS NULL
                AND audio_object_key IS NULL
                AND audio_content_type IS NULL
                AND audio_size_bytes IS NULL
                AND audio_duration_millis IS NULL
                AND audio_sha256 IS NULL)
            OR
            (message_type = 'AUDIO'
                AND content IS NULL
                AND client_message_id IS NOT NULL
                AND audio_bucket IS NOT NULL
                AND audio_object_key IS NOT NULL
                AND audio_content_type IS NOT NULL
                AND audio_size_bytes IS NOT NULL
                AND audio_size_bytes > 0
                AND audio_duration_millis IS NOT NULL
                AND audio_duration_millis > 0
                AND audio_sha256 IS NOT NULL
                AND length(audio_sha256) = 64)
        );

CREATE INDEX idx_chat_messages_reply_to_message
    ON chat_messages (reply_to_message_id)
    WHERE reply_to_message_id IS NOT NULL;

CREATE INDEX idx_chat_messages_reply_to_prompt_snapshot
    ON chat_messages (reply_to_prompt_snapshot_id)
    WHERE reply_to_prompt_snapshot_id IS NOT NULL;

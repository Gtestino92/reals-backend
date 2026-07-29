ALTER TABLE chat_messages
    ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN client_message_id UUID,
    ADD COLUMN audio_bucket VARCHAR(255),
    ADD COLUMN audio_object_key VARCHAR(1024),
    ADD COLUMN audio_content_type VARCHAR(128),
    ADD COLUMN audio_size_bytes BIGINT,
    ADD COLUMN audio_duration_millis BIGINT,
    ADD COLUMN audio_sha256 VARCHAR(64);

ALTER TABLE chat_messages
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_message_type
        CHECK (message_type IN ('TEXT', 'AUDIO')),
    ADD CONSTRAINT ck_chat_messages_text_shape
        CHECK (
            (message_type = 'TEXT'
                AND content IS NOT NULL
                AND client_message_id IS NULL
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

CREATE UNIQUE INDEX uq_chat_messages_client_message
    ON chat_messages (chat_session_id, sender_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

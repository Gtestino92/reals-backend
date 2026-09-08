ALTER TABLE chat_messages
    DROP CONSTRAINT ck_chat_messages_reply_sender_shape;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_reply_sender_shape
        CHECK (
            (
                reply_to_message_id IS NULL
                AND reply_to_prompt_snapshot_id IS NULL
            )
            OR
            (
                message_type IN ('TEXT', 'AUDIO')
                AND client_message_id IS NOT NULL
            )
        );

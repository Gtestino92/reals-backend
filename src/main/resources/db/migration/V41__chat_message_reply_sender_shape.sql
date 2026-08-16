ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_reply_sender_shape
        CHECK (
            (
                reply_to_message_id IS NULL
                AND reply_to_prompt_snapshot_id IS NULL
            )
            OR
            (
                message_type = 'TEXT'
                AND client_message_id IS NOT NULL
            )
        );

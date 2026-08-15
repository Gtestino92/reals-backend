ALTER TABLE chat_messages
    ADD COLUMN reaction_type VARCHAR(16);

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_reaction_type
        CHECK (reaction_type IS NULL OR reaction_type IN ('HEART'));

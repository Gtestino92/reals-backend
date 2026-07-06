CREATE TABLE first_chat_guidance (
    id                              UUID         NOT NULL DEFAULT gen_random_uuid(),
    version                         BIGINT       NOT NULL DEFAULT 0,
    chat_id                         UUID         NOT NULL,
    current_question_id             VARCHAR(64)  NOT NULL,
    current_question_text           TEXT         NOT NULL,
    current_question_ordinal        INT          NOT NULL,
    current_question_activated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    user_a_next_requested_at        TIMESTAMP WITH TIME ZONE,
    user_b_next_requested_at        TIMESTAMP WITH TIME ZONE,
    completed_at                    TIMESTAMP WITH TIME ZONE,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_first_chat_guidance_chat
        UNIQUE (chat_id),

    CONSTRAINT fk_first_chat_guidance_chat
        FOREIGN KEY (chat_id)
        REFERENCES chats(id),

    CONSTRAINT ck_first_chat_guidance_question_ordinal
        CHECK (current_question_ordinal >= 1)
);

CREATE INDEX idx_first_chat_guidance_chat
    ON first_chat_guidance (chat_id);

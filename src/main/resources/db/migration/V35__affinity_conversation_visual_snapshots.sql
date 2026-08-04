CREATE TABLE conversation_prompt_snapshots (
    id                               UUID                     NOT NULL DEFAULT gen_random_uuid(),
    chat_id                          UUID                     NOT NULL,
    ordinal                          INT                      NOT NULL,
    source_type                      VARCHAR(16)              NOT NULL,
    source_question_id               VARCHAR(96)              NOT NULL,
    source_question_semantic_version INT,
    prompt_text                      TEXT                     NOT NULL,
    category_id                      VARCHAR(96),
    conversation_kind                VARCHAR(32),
    created_at                       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_conversation_prompt_snapshots_chat
        FOREIGN KEY (chat_id)
        REFERENCES chats(id),

    CONSTRAINT uq_conversation_prompt_snapshots_chat_ordinal
        UNIQUE (chat_id, ordinal),

    CONSTRAINT uq_conversation_prompt_snapshots_chat_source
        UNIQUE (chat_id, source_type, source_question_id),

    CONSTRAINT ck_conversation_prompt_snapshots_ordinal
        CHECK (ordinal >= 1),

    CONSTRAINT ck_conversation_prompt_snapshots_source_type
        CHECK (source_type IN ('AFFINITY', 'GENERIC')),

    CONSTRAINT ck_conversation_prompt_snapshots_conversation_kind
        CHECK (conversation_kind IS NULL OR conversation_kind IN ('SHARED_AFFINITY', 'CONSTRUCTIVE_CONTRAST')),

    CONSTRAINT ck_conversation_prompt_snapshots_affinity_fields
        CHECK (
            (
                source_type = 'AFFINITY'
                AND source_question_semantic_version IS NOT NULL
                AND category_id IS NOT NULL
                AND conversation_kind IS NOT NULL
            )
            OR
            (
                source_type = 'GENERIC'
                AND source_question_semantic_version IS NULL
                AND category_id IS NULL
                AND conversation_kind IS NULL
            )
        )
);

CREATE INDEX idx_conversation_prompt_snapshots_chat
    ON conversation_prompt_snapshots (chat_id);

CREATE TABLE visual_review_affinity_indicators (
    id             UUID                     NOT NULL DEFAULT gen_random_uuid(),
    match_id       UUID                     NOT NULL,
    ordinal        INT                      NOT NULL,
    category_id    VARCHAR(96)              NOT NULL,
    category_title TEXT                     NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_visual_review_affinity_indicators_match
        FOREIGN KEY (match_id)
        REFERENCES matches(id),

    CONSTRAINT uq_visual_review_affinity_indicators_match_ordinal
        UNIQUE (match_id, ordinal),

    CONSTRAINT uq_visual_review_affinity_indicators_match_category
        UNIQUE (match_id, category_id),

    CONSTRAINT ck_visual_review_affinity_indicators_ordinal
        CHECK (ordinal >= 1)
);

CREATE INDEX idx_visual_review_affinity_indicators_match
    ON visual_review_affinity_indicators (match_id);

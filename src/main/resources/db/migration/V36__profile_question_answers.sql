CREATE TABLE profile_question_answers (
    id                        UUID                     NOT NULL DEFAULT gen_random_uuid(),
    version                   BIGINT                   NOT NULL DEFAULT 0,
    profile_id                UUID                     NOT NULL,
    question_id               VARCHAR(64)              NOT NULL,
    question_semantic_version INTEGER                  NOT NULL,
    answer_text               VARCHAR(160)             NOT NULL,
    selected_position         SMALLINT,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_profile_question_answers_profile
        FOREIGN KEY (profile_id)
        REFERENCES profiles(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_profile_question_answers_profile_question
        UNIQUE (profile_id, question_id),

    CONSTRAINT ck_profile_question_answers_semantic_version
        CHECK (question_semantic_version >= 1),

    CONSTRAINT ck_profile_question_answers_answer_text_length
        CHECK (char_length(btrim(answer_text)) BETWEEN 1 AND 160),

    CONSTRAINT ck_profile_question_answers_selected_position
        CHECK (selected_position IS NULL OR selected_position BETWEEN 1 AND 3)
);

CREATE INDEX idx_profile_question_answers_profile
    ON profile_question_answers (profile_id);

CREATE INDEX idx_profile_question_answers_question
    ON profile_question_answers (question_id);

CREATE INDEX idx_profile_question_answers_profile_selected
    ON profile_question_answers (profile_id, selected_position)
    WHERE selected_position IS NOT NULL;

CREATE UNIQUE INDEX uq_profile_question_answers_profile_selected_position
    ON profile_question_answers (profile_id, selected_position)
    WHERE selected_position IS NOT NULL;

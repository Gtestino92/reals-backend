CREATE TABLE affinity_question_answers (
    id                        UUID                     NOT NULL DEFAULT gen_random_uuid(),
    version                   BIGINT                   NOT NULL DEFAULT 0,
    profile_id                UUID                     NOT NULL,
    question_id               VARCHAR(96)              NOT NULL,
    question_semantic_version INTEGER                  NOT NULL,
    answer_code               VARCHAR(96)              NOT NULL,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_affinity_question_answers_profile
        FOREIGN KEY (profile_id)
        REFERENCES profiles(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_affinity_question_answers_profile_question
        UNIQUE (profile_id, question_id)
);

CREATE INDEX idx_affinity_question_answers_profile
    ON affinity_question_answers (profile_id);

CREATE INDEX idx_affinity_question_answers_question
    ON affinity_question_answers (question_id);

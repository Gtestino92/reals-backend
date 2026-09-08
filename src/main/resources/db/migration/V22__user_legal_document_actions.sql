CREATE TABLE user_legal_document_actions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    document_version VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    acted_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_user_legal_document_actions_user
        FOREIGN KEY (user_id)
        REFERENCES users(id),

    CONSTRAINT uq_user_legal_document_action_user_type_version
        UNIQUE (user_id, document_type, document_version)
);

CREATE INDEX idx_user_legal_document_actions_user_id
    ON user_legal_document_actions (user_id);

CREATE INDEX idx_user_legal_document_actions_user_type
    ON user_legal_document_actions (user_id, document_type);

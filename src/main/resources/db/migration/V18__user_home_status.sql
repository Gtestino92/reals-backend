CREATE TABLE user_home_status (
    user_id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    dirty BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_user_home_status_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

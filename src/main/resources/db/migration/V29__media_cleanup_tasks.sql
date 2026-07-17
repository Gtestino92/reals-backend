CREATE TABLE media_cleanup_tasks (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    version BIGINT NOT NULL DEFAULT 0,
    operation VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT ck_media_cleanup_tasks_operation
        CHECK (operation IN ('DELETE_OBJECT')),

    CONSTRAINT ck_media_cleanup_tasks_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'FAILED')),

    CONSTRAINT ck_media_cleanup_tasks_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT uq_media_cleanup_tasks_delete_object
        UNIQUE (operation, storage_provider, bucket, object_key)
);

CREATE INDEX idx_media_cleanup_tasks_due
    ON media_cleanup_tasks (status, next_attempt_at, created_at, id);

CREATE INDEX idx_media_cleanup_tasks_processing_lease
    ON media_cleanup_tasks (status, lease_until)
    WHERE status = 'PROCESSING';

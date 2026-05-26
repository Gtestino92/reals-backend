# Local H2 Fixes

These notes are for local `local-nodb` databases that were created before entity/schema fixes.

## Chat Decisions Schema

If approving the first chat fails with:

```text
NULL not allowed for column "USER_ID"
insert into chat_decisions (...)
```

the local H2 table still has the old `chat_decisions` shape. The current entity expects:

- `chat_id`
- `match_id`
- `user_a_decision`
- `user_b_decision`

For a local test database, the recommended repair is to stop the app, delete the local H2 database files under `data/`, start the app again, and restart the Bruno flow from `00 Setup Run Variables`.

The local profile uses Hibernate `ddl-auto: update`, so a fresh local database will be created from the current entities. This is acceptable while the project is still using a single `V1__init.sql` and local data is disposable.

If you need to preserve the rest of the local database, this targeted SQL also repairs only `chat_decisions`:

```sql
DROP TABLE IF EXISTS chat_decisions;

CREATE TABLE chat_decisions (
    id UUID NOT NULL DEFAULT random_uuid(),
    chat_id UUID NOT NULL,
    match_id UUID NOT NULL,
    user_a_decision VARCHAR(16),
    user_b_decision VARCHAR(16),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_chat_decision_chat UNIQUE (chat_id),
    CONSTRAINT uq_chat_decision_match UNIQUE (match_id),
    CONSTRAINT fk_chat_decision_chat FOREIGN KEY (chat_id) REFERENCES chats(id),
    CONSTRAINT fk_chat_decision_match FOREIGN KEY (match_id) REFERENCES matches(id)
);

CREATE INDEX idx_chat_decision_match ON chat_decisions (match_id);
CREATE INDEX idx_chat_decision_chat ON chat_decisions (chat_id);
```

This deletes local chat-decision data only. For a purely local happy-path run, it is fine to restart the Bruno flow after applying it.

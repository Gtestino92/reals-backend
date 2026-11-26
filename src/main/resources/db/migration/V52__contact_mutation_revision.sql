ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_mutation_revision uuid DEFAULT gen_random_uuid() NOT NULL;

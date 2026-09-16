ALTER TABLE skw.entries
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (jsonb_to_tsvector('simple'::regconfig, properties, '["string"]'::jsonb)) STORED;

CREATE INDEX entries_search_vector_idx
    ON skw.entries USING GIN (search_vector);

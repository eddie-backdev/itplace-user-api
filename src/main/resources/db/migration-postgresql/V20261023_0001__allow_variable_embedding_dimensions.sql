ALTER TABLE benefit_search_document
    DROP CONSTRAINT benefit_search_document_embedding_dimensions_check;

ALTER TABLE benefit_search_document
    ALTER COLUMN embedding TYPE vector
    USING embedding::vector;

ALTER TABLE benefit_search_document
    ADD CONSTRAINT ck_bsd_embedding_dimensions
        CHECK (embedding_dimensions BETWEEN 1 AND 16000
            AND (embedding IS NULL OR vector_dims(embedding) = embedding_dimensions));

-- Mirrors exactly what Spring AI's PgVectorStore would auto-create
-- (spring.ai.vectorstore.pgvector.initialize-schema=false), so this one table's
-- schema stays under Flyway's tracked history like every other table.
-- Tenant isolation is done via the `metadata` JSON column (key "tenant_id"),
-- filtered at query time through VectorStore.SearchRequest.filterExpression(...).
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1536)
);

CREATE INDEX ON vector_store USING HNSW (embedding vector_cosine_ops);

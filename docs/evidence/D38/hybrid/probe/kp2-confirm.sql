SET client_encoding TO 'UTF8';
\pset footer off
-- KP2 补充确认：chunkId 连接键形态 = PG uuid id；metadata 为 json 非 jsonb
SELECT 'sample-uuid-rows' AS tag;
SELECT id, left(content, 18) AS content_head,
       metadata->>'documentId' AS doc,
       (metadata->>'chunkIndex') AS cidx,
       metadata->>'vectorSpaceId' AS space
FROM vector_store
WHERE metadata->>'documentId' = 'DOC-CNC-COOLING-001'
ORDER BY (metadata->>'chunkIndex')::int;
SELECT 'metadata-column-type' AS tag;
SELECT data_type FROM information_schema.columns
WHERE table_name = 'vector_store' AND column_name IN ('id','content','metadata','embedding')
ORDER BY ordinal_position;
SELECT 'residual-rows-docids' AS tag;
SELECT DISTINCT metadata->>'documentId' AS doc, count(*) FROM vector_store
GROUP BY 1 ORDER BY 1;

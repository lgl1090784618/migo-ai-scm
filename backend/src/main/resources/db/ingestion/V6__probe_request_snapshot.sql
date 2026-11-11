-- Historical rows remain NULL: never reconstruct unknown past requests from current configuration.
ALTER TABLE collection_probe ADD COLUMN target_origin text;
ALTER TABLE collection_probe ADD COLUMN request_snapshot jsonb;
ALTER TABLE collection_probe ADD COLUMN authentication_kind text;

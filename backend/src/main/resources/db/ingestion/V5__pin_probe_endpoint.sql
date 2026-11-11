ALTER TABLE collection_probe ADD COLUMN endpoint_id text REFERENCES collection_endpoint(id);
UPDATE collection_probe r SET endpoint_id=p.endpoint_id FROM collection_profile p WHERE p.source_id=r.source_id;
ALTER TABLE collection_probe ALTER COLUMN endpoint_id SET NOT NULL;

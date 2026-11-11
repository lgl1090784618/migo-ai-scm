ALTER TABLE collection_endpoint ADD COLUMN platform_code text;
UPDATE collection_endpoint SET platform_code='migo-wms' WHERE id='wms.sku-stock';
ALTER TABLE collection_endpoint ALTER COLUMN platform_code SET NOT NULL;

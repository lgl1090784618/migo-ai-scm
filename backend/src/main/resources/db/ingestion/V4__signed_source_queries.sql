ALTER TABLE collection_endpoint ADD COLUMN success_code integer NOT NULL DEFAULT 200;
INSERT INTO collection_endpoint(id,name,method,path,items_pointer,request_template,review_status,documentation_url,platform_code,success_code) VALUES
 ('xinwuyun.produce-orders','新物云：查询生产加工单','POST','/openapi/api/produce-order/query','/data',
 '{"date_type":1,"page":1,"page_size":10,"modified_start":"","modified_end":""}',
 'APPROVED','https://dm-scm-test.emigo.cn/openapi/v3/api-docs?group=miguo','xinwuyun',0);
ALTER TABLE collection_probe ADD COLUMN import_dataset_id uuid REFERENCES dataset(id);
ALTER TABLE collection_probe ADD COLUMN import_schema_version integer;
ALTER TABLE collection_probe ADD COLUMN import_producer_id uuid REFERENCES api_client(id);
ALTER TABLE collection_probe ADD COLUMN import_batch_id uuid REFERENCES ingest_batch(id);
CREATE TABLE collection_producer (
 dataset_id uuid PRIMARY KEY REFERENCES dataset(id), producer_id uuid NOT NULL UNIQUE REFERENCES api_client(id)
);

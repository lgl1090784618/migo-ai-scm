CREATE TABLE collection_endpoint (
 id text PRIMARY KEY, name text NOT NULL, method text NOT NULL CHECK(method IN ('GET','POST')),
 path text NOT NULL, items_pointer text NOT NULL, request_template jsonb NOT NULL,
 review_status text NOT NULL CHECK(review_status IN ('APPROVED','PENDING')),
 documentation_url text NOT NULL
);
INSERT INTO collection_endpoint VALUES
 ('wms.sku-stock','WMS：查询SKU库存','POST','/api/stockmanage/getstocksku','/result',
 '{"conditions":[],"pageIndex":1,"pageSize":5,"isExcel":false,"isSync":false}',
 'APPROVED','http://wms-api-test.emigo.it/swagger/migo.wms/swagger.json');
CREATE TABLE collection_profile (
 source_id uuid PRIMARY KEY REFERENCES source_instance(id), tenant_id text NOT NULL,
 endpoint_id text NOT NULL REFERENCES collection_endpoint(id), base_url text NOT NULL,
 request_json jsonb NOT NULL, credential_kind text NOT NULL DEFAULT 'BEARER', credential_cipher text,
 credential_updated_at timestamptz, revision integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE collection_probe (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, source_id uuid NOT NULL REFERENCES source_instance(id),
 profile_revision integer NOT NULL, state text NOT NULL, http_status integer, record_count integer,
 error_code text, raw_object_key text, raw_sha256 text, request_id uuid NOT NULL,
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz
);
CREATE INDEX collection_probe_source_idx ON collection_probe(tenant_id,source_id,started_at DESC,id DESC);

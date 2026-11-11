ALTER TABLE collection_endpoint ADD COLUMN page_field text;
ALTER TABLE collection_endpoint ADD COLUMN size_field text;
ALTER TABLE collection_endpoint ADD COLUMN next_pointer text;
ALTER TABLE collection_endpoint ADD COLUMN total_pointer text;
UPDATE collection_endpoint SET page_field='pageIndex',size_field='pageSize' WHERE id='wms.sku-stock';
UPDATE collection_endpoint SET page_field='page',size_field='page_size',next_pointer='/hasNext' WHERE id='xinwuyun.produce-orders';
UPDATE collection_endpoint SET page_field='pageNum',size_field='pageSize',total_pointer='/data/total' WHERE id IN ('gateway.factory-stock','gateway.fba-stock');
ALTER TABLE collection_endpoint ALTER COLUMN page_field SET NOT NULL;
ALTER TABLE collection_endpoint ALTER COLUMN size_field SET NOT NULL;
ALTER TABLE collection_probe ADD COLUMN has_more boolean;
CREATE TABLE collection_schedule (
 source_id uuid PRIMARY KEY REFERENCES source_instance(id), tenant_id text NOT NULL,
 dataset_id uuid NOT NULL REFERENCES dataset(id), enabled boolean NOT NULL DEFAULT false,
 interval_seconds integer NOT NULL CHECK(interval_seconds BETWEEN 60 AND 86400),
 max_pages integer NOT NULL CHECK(max_pages BETWEEN 1 AND 100),
 max_records integer NOT NULL CHECK(max_records BETWEEN 1 AND 10000),
 window_seconds integer NOT NULL DEFAULT 0 CHECK(window_seconds BETWEEN 0 AND 5097600),
 query_timezone text NOT NULL DEFAULT 'Asia/Shanghai', revision integer NOT NULL DEFAULT 1,
 next_run_at timestamptz NOT NULL DEFAULT now(), last_result text, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE collection_job (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, source_id uuid NOT NULL REFERENCES source_instance(id),
 dataset_id uuid NOT NULL REFERENCES dataset(id), trigger_type text NOT NULL,
 idempotency_key text NOT NULL, request_hash char(64) NOT NULL, schema_version integer NOT NULL, mapping_version integer, state text NOT NULL DEFAULT 'QUEUED',
 endpoint_id text NOT NULL REFERENCES collection_endpoint(id), base_url text NOT NULL,
 profile_revision integer NOT NULL, config_fingerprint char(64) NOT NULL,
 profile_request_json jsonb NOT NULL, query_json jsonb NOT NULL,
 max_pages integer NOT NULL, max_records integer NOT NULL, next_page integer NOT NULL,
 pages_done integer NOT NULL DEFAULT 0, record_count integer NOT NULL DEFAULT 0,
 limit_hit boolean NOT NULL DEFAULT false, query_finished boolean NOT NULL DEFAULT false, failures integer NOT NULL DEFAULT 0, attempts integer NOT NULL DEFAULT 0,
 fence bigint NOT NULL DEFAULT 0, lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(),
 error_code text, request_id uuid NOT NULL, created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
 UNIQUE(tenant_id,idempotency_key)
);
CREATE UNIQUE INDEX collection_job_active_source ON collection_job(tenant_id,source_id)
 WHERE state IN ('QUEUED','RUNNING','RETRY','WAITING_AUTH','WAITING_PROJECTION','WAITING_SOURCE');
CREATE INDEX collection_job_ready ON collection_job(state,available_at) WHERE state IN ('QUEUED','RETRY','WAITING_PROJECTION','RUNNING');
CREATE INDEX collection_job_list ON collection_job(tenant_id,created_at DESC,id DESC);
CREATE TABLE collection_job_page (
 job_id uuid NOT NULL REFERENCES collection_job(id), page_no integer NOT NULL,
 query_json jsonb NOT NULL, probe_id uuid REFERENCES collection_probe(id), batch_id uuid REFERENCES ingest_batch(id),
 state text NOT NULL DEFAULT 'PENDING', attempts integer NOT NULL DEFAULT 0, record_count integer,
 has_more boolean, error_code text, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(job_id,page_no)
);
ALTER TABLE collection_probe ADD COLUMN job_id uuid REFERENCES collection_job(id);
ALTER TABLE collection_probe ADD COLUMN page_no integer;
CREATE INDEX collection_probe_job ON collection_probe(job_id,started_at);

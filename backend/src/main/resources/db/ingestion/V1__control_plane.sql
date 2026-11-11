CREATE TABLE platform (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, code varchar(64) NOT NULL,
 name varchar(120) NOT NULL, category varchar(32) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,code)
);
CREATE TABLE source_instance (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, platform_id uuid NOT NULL REFERENCES platform(id),
 name varchar(120) NOT NULL, mode varchar(16) NOT NULL CHECK(mode IN ('PUSH','PULL')),
 connector_type varchar(32) NOT NULL, environment varchar(24) NOT NULL CHECK(environment IN ('test','unverified')),
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','ACTIVE','PAUSED')),
 config_json jsonb NOT NULL DEFAULT '{}', row_version integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,name)
);
CREATE TABLE dataset (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, source_id uuid NOT NULL REFERENCES source_instance(id),
 code varchar(64) NOT NULL, name varchar(120) NOT NULL, entity_type varchar(64) NOT NULL,
 grain varchar(240) NOT NULL, state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','ACTIVE','PAUSED')),
 active_schema_version integer, current_mapping integer, row_version integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,source_id,code)
);
CREATE TABLE schema_version (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, dataset_id uuid NOT NULL REFERENCES dataset(id), version integer NOT NULL,
 document jsonb NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED')),
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 UNIQUE(tenant_id,dataset_id,version)
);
CREATE TABLE mapping_version (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, dataset_id uuid NOT NULL REFERENCES dataset(id), version integer NOT NULL,
 document jsonb NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED')),
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 UNIQUE(tenant_id,dataset_id,version)
);
CREATE FUNCTION published_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='PUBLISHED' THEN RAISE EXCEPTION 'Published version is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER schema_immutable BEFORE UPDATE OR DELETE ON schema_version FOR EACH ROW EXECUTE FUNCTION published_version_immutable();
CREATE TRIGGER mapping_immutable BEFORE UPDATE OR DELETE ON mapping_version FOR EACH ROW EXECUTE FUNCTION published_version_immutable();
CREATE TABLE api_client (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, name varchar(120) NOT NULL,
 source_id uuid NOT NULL REFERENCES source_instance(id), dataset_id uuid NOT NULL REFERENCES dataset(id),
 token_hash char(64) NOT NULL UNIQUE, enabled boolean NOT NULL DEFAULT true,
 created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), last_used_at timestamptz
);
CREATE TABLE ingest_batch (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, producer_id uuid NOT NULL REFERENCES api_client(id),
 source_id uuid NOT NULL REFERENCES source_instance(id), dataset_id uuid NOT NULL REFERENCES dataset(id),
 idempotency_key varchar(128) NOT NULL, request_hash char(64) NOT NULL,
 object_key text NOT NULL, object_sha256 char(64) NOT NULL,
 schema_version integer NOT NULL, mapping_version integer,
 status varchar(32) NOT NULL DEFAULT 'RECEIVED', event_count integer NOT NULL CHECK(event_count BETWEEN 1 AND 1000),
 applied_count integer NOT NULL DEFAULT 0, duplicate_count integer NOT NULL DEFAULT 0, rejected_count integer NOT NULL DEFAULT 0,
 request_id uuid NOT NULL, received_at timestamptz NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
 error_code text, UNIQUE(tenant_id,producer_id,idempotency_key)
);
CREATE TABLE raw_event (
 id uuid NOT NULL, tenant_id text NOT NULL, batch_id uuid NOT NULL REFERENCES ingest_batch(id),
 received_at timestamptz NOT NULL, ordinal integer NOT NULL, source_id uuid NOT NULL,
 event_id varchar(200) NOT NULL, event_hash char(64) NOT NULL, entity_key jsonb NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'PENDING', error_code text, error_detail jsonb,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,received_at,id), UNIQUE(tenant_id,received_at,batch_id,ordinal)
) PARTITION BY RANGE(received_at);
DO $$ DECLARE start_month date; next_month date; BEGIN
 FOR i IN 0..1 LOOP
  start_month=(date_trunc('month',now() AT TIME ZONE 'UTC')+make_interval(months=>i))::date;
  next_month=(start_month+interval '1 month')::date;
  EXECUTE format('CREATE TABLE raw_event_%s PARTITION OF raw_event FOR VALUES FROM (%L) TO (%L)',to_char(start_month,'YYYYMM'),start_month::text||' 00:00:00+00',next_month::text||' 00:00:00+00');
 END LOOP;
END $$;
CREATE TABLE raw_event_default PARTITION OF raw_event DEFAULT;
CREATE TABLE event_identity (
 tenant_id text NOT NULL, source_id uuid NOT NULL, event_id varchar(200) NOT NULL,
 payload_hash char(64) NOT NULL, first_raw_id uuid NOT NULL, first_seen_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,source_id,event_id)
);
CREATE TABLE outbox_job (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, batch_id uuid NOT NULL UNIQUE REFERENCES ingest_batch(id),
 state varchar(24) NOT NULL DEFAULT 'PENDING', attempts integer NOT NULL DEFAULT 0, fence bigint NOT NULL DEFAULT 0,
 lease_until timestamptz, available_at timestamptz NOT NULL DEFAULT now(), last_error text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE audit_event (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, actor text NOT NULL, action varchar(80) NOT NULL,
 resource_type varchar(64) NOT NULL, resource_id text NOT NULL, detail jsonb NOT NULL DEFAULT '{}',
 request_id uuid NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX source_list_idx ON source_instance(tenant_id,created_at DESC,id DESC);
CREATE INDEX dataset_list_idx ON dataset(tenant_id,created_at DESC,id DESC);
CREATE INDEX batch_list_idx ON ingest_batch(tenant_id,received_at DESC,id DESC);
CREATE INDEX batch_source_idx ON ingest_batch(tenant_id,source_id,received_at DESC);
CREATE INDEX raw_batch_idx ON raw_event(tenant_id,batch_id,received_at,ordinal);
CREATE INDEX raw_error_idx ON raw_event(tenant_id,status,received_at DESC,id DESC);
CREATE INDEX outbox_ready_idx ON outbox_job(state,available_at) WHERE state IN ('PENDING','RETRY','PROCESSING');
CREATE INDEX audit_list_idx ON audit_event(tenant_id,occurred_at DESC,id DESC);

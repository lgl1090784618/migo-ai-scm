-- Generic, versioned event projections only. No source-system business rules.
CREATE TABLE business_inbox (
 tenant_id text NOT NULL, consumer_name text NOT NULL, source_id uuid NOT NULL, event_id text NOT NULL,
 projection_version text NOT NULL, payload_hash char(64) NOT NULL, first_raw_id uuid NOT NULL,
 applied_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,consumer_name,source_id,event_id,projection_version)
);
CREATE TABLE standard_event_projection (
 id uuid PRIMARY KEY, tenant_id text NOT NULL, source_id uuid NOT NULL, dataset_id uuid NOT NULL,
 batch_id uuid NOT NULL, raw_id uuid NOT NULL, event_id text NOT NULL, entity_key jsonb NOT NULL,
 projection_version text NOT NULL, schema_version integer NOT NULL, mapping_version integer,
 operation text NOT NULL, payload jsonb NOT NULL, occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,source_id,event_id,projection_version)
);
CREATE INDEX projection_list_idx ON standard_event_projection(tenant_id,dataset_id,created_at DESC,id DESC);

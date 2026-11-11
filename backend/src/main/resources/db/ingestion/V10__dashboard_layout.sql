-- 看板布局：每个用户每页可保存多套模板，layout 为 [{id,x,y,w,h}] 的 JSON 数组
CREATE TABLE dashboard_layout (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    owner text NOT NULL,
    page varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    layout jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, owner, page, name)
);
CREATE INDEX dashboard_layout_lookup ON dashboard_layout (tenant_id, owner, page);

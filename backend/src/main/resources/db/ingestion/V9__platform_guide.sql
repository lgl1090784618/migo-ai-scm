ALTER TABLE platform ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE platform ADD COLUMN guide_json jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE platform ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE platform SET description='米果自研 WMS 仓储系统（.NET），只读接入 SKU 库存、批次、库位、出入库单、B2C 订单与财务明细。认证为应用签名授权。', guide_json='{"summary":"通过应用签名授权（双层 MD5）只读查询库存与单据，支持定时轮询采集。","auth":[{"kind":"WMS_APP","label":"WMS 应用签名授权","note":"服务端按 md5(md5(appId+nonce+secret)) 签名后调用 /api/user/getauthtoken 取短期 Token，有效期 1—60 分钟。"}],"steps":["在平台与连接登记 test / HTTP / 主动查询连接","在接口配置与试查选择已核实的 WMS 只读接口并录入 appId、appSecret","执行单次只读试查确认可返回数据","在正式采集任务中配置页数、记录数、间隔后启用定时计划"],"polling":"支持轮询：CollectionJobs 定时领任务，按 pageIndex/pageSize 翻页，达到页数或记录上限会标记 LIMIT_REACHED。"}'::jsonb WHERE code='migo-wms';

UPDATE platform SET description='新物云 SCM 打样与生产进度事实源，只读接入生产订单查询。认证为 OpenAPI MD5 签名。', guide_json='{"summary":"通过 OpenAPI 签名（account/secret）只读查询打样与生产进度，支持滚动时间窗口轮询。","auth":[{"kind":"XWY_SIGNING","label":"新物云 OpenAPI 签名","note":"sign=md5(serverName.account.secret.timestamp)，拼接 account/timestamp/sign 查询参数。"}],"steps":["在平台与连接登记 test / HTTP / 主动查询连接","在接口配置与试查选择生产订单接口并录入 account、secret、serverName","执行单次只读试查确认返回","在正式采集任务配置滚动时间窗口后启用定时计划"],"polling":"支持轮询：使用 page/page_size 分页与 hasNext 判断，窗口秒数仅限新物云且不小于 60 秒。"}'::jsonb WHERE code='xinwuyun';

UPDATE platform SET description='米果供应链协同微服务，只读接入采购单、质检单、批次库存、预约单与主体库存。认证为微服务账号密码登录。', guide_json='{"summary":"通过微服务账号密码 + 验证码登录取 JWT 后只读查询供应链数据，支持定时轮询采集。","auth":[{"kind":"LOGIN_ACCOUNT","label":"微服务登录账号","note":"账号密码经平台登录后换取 JWT Token，Token 保留在服务端，到期后需重新验证码登录。"}],"steps":["在平台与连接登记 test / HTTP / 主动查询连接","在接口配置与试查录入登录账号并完成验证码登录","选择已核实的供应链只读接口并试查","在正式采集任务配置间隔后启用定时计划"],"polling":"支持轮询：使用 pageNum/pageSize 分页与 data/total 判断，登录失效后任务进入 WAITING_AUTH 等待重新授权。"}'::jsonb WHERE code='migo-supply';

UPDATE platform SET description='米果物流 TMS 微服务，只读接入海外仓库存、发货需求、补货建议等物流数据。认证为微服务账号密码登录。', guide_json='{"summary":"通过微服务账号密码 + 验证码登录取 JWT 后只读查询物流与海外仓数据，支持定时轮询采集。","auth":[{"kind":"LOGIN_ACCOUNT","label":"微服务登录账号","note":"账号密码经平台登录后换取 JWT Token，Token 保留在服务端，到期后需重新验证码登录。"}],"steps":["在平台与连接登记 test / HTTP / 主动查询连接","在接口配置与试查录入登录账号并完成验证码登录","选择已核实的物流只读接口并试查","在正式采集任务配置间隔后启用定时计划"],"polling":"支持轮询：使用 pageNum/pageSize 分页与 data/total 判断，登录失效后任务进入 WAITING_AUTH 等待重新授权。"}'::jsonb WHERE code='migo-tms';

UPDATE platform SET description='米果运营中台，提供品牌、SKU、SPU、店铺、运营团队等销售主数据。当前通过 MCP 只读查询，HTTP 通道待核实。', guide_json='{"summary":"销售主数据源，当前以 MCP 只读查询为主，HTTP 只读接口待核实后接入。","auth":[],"steps":["在平台与连接登记连接","核实可用只读查询接口","执行试查并登记数据集"],"polling":"待核实 HTTP 只读接口后接入轮询。"}'::jsonb WHERE code='migo-sell';

UPDATE platform SET description='吉客云 ERP，提供货品、仓库、库存、出入库单与采购数据。认证为 appKey/appSecret 应用签名。', guide_json='{"summary":"吉客云开放平台 ERP 数据，通过应用签名只读查询货品、仓库、库存与单据。","auth":[{"kind":"BEARER","label":"应用签名 / Bearer","note":"按吉客云开放平台规范签名换取访问凭证，或录入已取得的 Bearer Token。"}],"steps":["在平台与连接登记连接","录入吉客云应用凭据","选择只读接口试查","在正式采集任务启用定时计划"],"polling":"支持轮询：按接口分页参数翻页采集。"}'::jsonb WHERE code='jackyun';

UPDATE platform SET description='领星 ERP，提供多平台订单与物流数据。认证为 OpenAPI 凭据，只读查询订单。', guide_json='{"summary":"领星 OpenAPI 多平台订单只读接入。","auth":[{"kind":"BEARER","label":"领星 OpenAPI","note":"录入领星 OpenAPI 凭据后只读查询多平台订单。"}],"steps":["在平台与连接登记连接","录入领星凭据","选择订单查询接口试查","在正式采集任务启用定时计划"],"polling":"支持轮询：按时间窗分页拉取订单。"}'::jsonb WHERE code='lingxing';

UPDATE platform SET description='观远 BI，本期先不接入（MCP 通道待补 MCP 执行器）。', guide_json='{"summary":"观远 BI 通过 MCP 只读接入，本期暂不接入。","auth":[],"steps":["补充 MCP 执行器或反解 HTTP 通道","核实只读接口","试查并登记数据集"],"polling":"待接入后确认。"}'::jsonb WHERE code='guanyuan';

UPDATE platform SET description='飞书多维表，人工数据与市场候选池入口，通过飞书 Base 只读接入。', guide_json='{"summary":"飞书多维表作为人工数据与市场候选池入口，只读接入。","auth":[],"steps":["在平台与连接登记连接","核实多维表只读读取方式","试查并登记数据集"],"polling":"按需轮询多维表记录。"}'::jsonb WHERE code='feishu-base';

UPDATE platform SET description='FastMoss 市场数据平台，只读接入市场与选品数据。', guide_json='{"summary":"FastMoss 市场数据只读接入。","auth":[{"kind":"BEARER","label":"FastMoss 凭据","note":"录入 FastMoss 凭据后只读查询市场与选品数据。"}],"steps":["在平台与连接登记连接","录入凭据","选择只读接口试查","启用定时计划"],"polling":"支持轮询：按接口分页参数采集。"}'::jsonb WHERE code='fastmoss';

# MOSSDOOM 经营系统

当前已部署数据采集管理主体框架及WMS、新物云、协同和物流微服务的四个只读查询适配，完成25条真实测试观测的原文留存、批次导入和通用投影。经营域实体关联、全量/定时采集和AI经营决策尚未实现。

当前实现与认证方式优先阅读：[真实接口接入与配置管理](docs/真实接口接入与配置管理.md)。

## 当前入口

- 测试机：Linux `192.168.10.154`，4核8G；应用地址 `https://192.168.10.154:8443/`。
- 管理用户 `admin`，只读用户 `viewer`；密码由部署时生成，在服务器 `/etc/mossdoom/secrets/app_admin_password`、`app_viewer_password` 读取。两个平台独立认证，当前测试登录用户名均为 `admin`，密码按用户要求已统一，保存在各自受控凭据文件中。
- Prometheus 地址为 `https://192.168.10.154:8443/prometheus/`。它负责基础设施和应用指标，采集批次管理在应用首页。
- 当前测试证书为自签名证书；生产部署使用可信证书。
- 详细说明见 [主体框架说明](docs/主体框架说明.md)、[部署与迁移](docs/部署与迁移.md)、[统一接收协议](docs/统一接收协议.md)。

## 源码和构建

`backend` 为 Java21 / Spring Boot3.5.16 应用；`frontend` 为 React / TypeScript / Vite 管理界面；`deploy` 为测试部署和端到端验证脚本。数据库由 Flyway 按采集库、业务库分别迁移。

构建顺序为前端 `npm ci && npm run build`，后端 `mvn package`。为遵守本机文件归属约定，设置 `MOSS_FRONTEND_OUT_DIR` 到 E 盘任务目录，并向 Maven 传入 `-Dmoss.frontend.directory=<上述目录>`、`-Dmoss.build.directory=<E盘构建目录>`、`-Dmaven.repo.local=<E盘依赖目录>`。前端静态文件打入后端 JAR，不需生产运行 Node 服务。

Docker 构建上下文包含 `app.jar` 和 `deploy/Dockerfile`；基础 Java 镜像必须传入锁定摘要的 `JAVA_IMAGE`。完整基础设施配置保留在既有任务交付目录中，安装脚本基于测试机已有基础环境生成独立发布目录，不适用于空机器直接执行。

## 产物归属

源码位于 `E:\codex\project\MOSSDOOM经营系统`；构建、验证日志和分发产物统一位于既有任务目录 `E:\codex\data\tasks\MOSSDOOM经营系统\2026-11-09-全链路经营系统架构`。凭据仅在 `E:\codex-private` 或服务器受控凭据目录中，不进入代码和交付包。

## 验证边界

协议单元测试覆盖 Schema 限制、身份边界、规范化指纹、精确金额与映射转换。`deploy/verify-framework.py` 在指定测试机创建标注为“框架验收”的合成数据，验证管理权限、幂等、异步处理、隔离、回读和分页，结束后暂停验收连接并吊销令牌。不会调用真实业务平台。测试报告中的通过数量和状态以实际日志为准；没有亿级容量或生产高可用验证结论。

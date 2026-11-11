#!/usr/bin/env bash
set -e
ROOT='/e/codex/project/MOSSDOOM经营系统'
FE='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/frontend-dist'
KEY='E:/codex-private/credentials/mossdoom/test-154-automation'
KNOWN='E:/codex-private/credentials/mossdoom-known-hosts'
JAR='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/backend-target/mossdoom-platform-0.1.0.jar'
DOCKERFILE="$ROOT/deploy/Dockerfile"
REFRESH="$ROOT/deploy/refresh-framework.sh"
SSHOPTS="-i $KEY -o UserKnownHostsFile=$KNOWN -o StrictHostKeyChecking=yes"

echo "=== 1. 前端构建 ==="
cd "$ROOT/frontend"
MOSS_FRONTEND_OUT_DIR="$FE" npm run build
echo "=== 前端产物 ==="
ls -l "$FE/assets" | tail -5
grep -o 'index-[A-Za-z0-9_-]*\.\(js\|css\)' "$FE/index.html"

echo "=== 2. 后端单测 + 打包 ==="
cd "$ROOT/backend"
bash "$ROOT/deploy/build-sixpiece-2.sh"

echo "=== 3. 部署前健康 ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || echo HEALTH_FAIL'

echo "=== 4. 上传并重建 ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo mkdir -p /tmp/mossdoom-framework-bundle && sudo chown migo:migo /tmp/mossdoom-framework-bundle'
scp $SSHOPTS "$JAR" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/app.jar
scp $SSHOPTS "$DOCKERFILE" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/Dockerfile
scp $SSHOPTS "$REFRESH" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/refresh-framework.sh
ssh $SSHOPTS migo@192.168.10.154 'sudo bash /tmp/mossdoom-framework-bundle/refresh-framework.sh' 2>&1 | tail -30

echo "=== 5. 等待 UP ==="
ssh $SSHOPTS migo@192.168.10.154 'for i in $(seq 1 60); do h=$(sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null); echo "$h" | grep -q "\"status\":\"UP\"" && { echo READY; break; }; sleep 2; done'

echo "=== 6. 验证 ==="
ssh $SSHOPTS migo@192.168.10.154 'echo -n "favicon.ico: "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.ico; echo -n "favicon.svg: "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.svg; echo -n "index: "; curl -sk https://192.168.10.154:8443/ | grep -o "index-[A-Za-z0-9_-]*\.js"' 2>&1
echo "BUILD_DEPLOY_DONE"

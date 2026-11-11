#!/usr/bin/env bash
set -e
KEY='E:/codex-private/credentials/mossdoom/test-154-automation'
KNOWN='E:/codex-private/credentials/mossdoom-known-hosts'
JAR='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/backend-target/mossdoom-platform-0.1.0.jar'
DOCKERFILE='E:/codex/project/MOSSDOOM经营系统/deploy/Dockerfile'
REFRESH='E:/codex/project/MOSSDOOM经营系统/deploy/refresh-framework.sh'
SSHOPTS="-i $KEY -o UserKnownHostsFile=$KNOWN -o StrictHostKeyChecking=yes"

echo "=== 部署前健康 ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || echo HEALTH_FAIL'

echo "=== 上传并重建 ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo mkdir -p /tmp/mossdoom-framework-bundle && sudo chown migo:migo /tmp/mossdoom-framework-bundle'
scp $SSHOPTS "$JAR" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/app.jar
scp $SSHOPTS "$DOCKERFILE" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/Dockerfile
scp $SSHOPTS "$REFRESH" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/refresh-framework.sh
ssh $SSHOPTS migo@192.168.10.154 'sudo bash /tmp/mossdoom-framework-bundle/refresh-framework.sh' 2>&1 | tail -25

echo "=== 等待 UP ==="
ssh $SSHOPTS migo@192.168.10.154 'for i in $(seq 1 60); do h=$(sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null); echo "$h" | grep -q "\"status\":\"UP\"" && { echo READY; break; }; sleep 2; done'

echo "=== 验证 ==="
ssh $SSHOPTS migo@192.168.10.154 'echo -n "favicon.ico -> "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.ico; echo -n "favicon.svg -> "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.svg; echo -n "unknown/path -> "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/no-such-page; echo -n "index js -> "; curl -sk https://192.168.10.154:8443/ | grep -o "index-[A-Za-z0-9_-]*\.js"'
echo "DEPLOY_UI_FIX_DONE"

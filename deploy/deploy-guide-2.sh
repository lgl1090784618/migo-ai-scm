#!/usr/bin/env bash
set -e
KEY='E:/codex-private/credentials/mossdoom/test-154-automation'
KNOWN='E:/codex-private/credentials/mossdoom-known-hosts'
JAR='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/backend-target/mossdoom-platform-0.1.0.jar'
DOCKERFILE='E:/codex/project/MOSSDOOM经营系统/deploy/Dockerfile'
REFRESH='E:/codex/project/MOSSDOOM经营系统/deploy/refresh-framework.sh'
SSHOPTS="-i $KEY -o UserKnownHostsFile=$KNOWN -o StrictHostKeyChecking=yes"

echo "=== health before ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || echo HEALTH_FAIL'

echo "=== prepare bundle on host ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo mkdir -p /tmp/mossdoom-framework-bundle && sudo chown migo:migo /tmp/mossdoom-framework-bundle'
scp $SSHOPTS "$JAR" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/app.jar
scp $SSHOPTS "$DOCKERFILE" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/Dockerfile
scp $SSHOPTS "$REFRESH" migo@192.168.10.154:/tmp/mossdoom-framework-bundle/refresh-framework.sh

echo "=== rebuild image + restart app (refresh-framework) ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo bash /tmp/mossdoom-framework-bundle/refresh-framework.sh' 2>&1 | tail -50

echo "=== wait for app UP ==="
ssh $SSHOPTS migo@192.168.10.154 'for i in $(seq 1 40); do h=$(sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null); echo "$h" | grep -q "\"status\":\"UP\"" && { echo READY; break; }; sleep 2; done'

echo "=== health after ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || echo HEALTH_FAIL'
echo "=== current symlink ==="
ssh $SSHOPTS migo@192.168.10.154 'readlink -f /opt/mossdoom/current'
echo "=== V9 migration check ==="
ssh $SSHOPTS migo@192.168.10.154 'sudo docker exec mossdoom-test-app-1 sh -c "echo" 2>/dev/null; sudo docker exec mossdoom-test-postgres-1 psql -U moss_admin -d moss_ingestion -tAc "SELECT version,description,success FROM flyway_schema_history WHERE version='\''9'\''"' 2>/dev/null || echo V9_CHECK_FAIL'
echo "DEPLOY_DONE"

#!/usr/bin/env bash
# 前端构建 → 后端单测+打包 → 上传 → 重建镜像 → 等健康 → 校验页面版本
# 失败即非零退出：SSH 瞬时失败会重试，健康检查不到 UP 或页面版本不匹配都会 FAILED。
set -euo pipefail

ROOT='/e/codex/project/MOSSDOOM经营系统'
FE='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/frontend-dist'
KEY='E:/codex-private/credentials/mossdoom/test-154-automation'
KNOWN='E:/codex-private/credentials/mossdoom-known-hosts'
JAR='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/backend-target/mossdoom-platform-0.1.0.jar'
DOCKERFILE='E:/codex/project/MOSSDOOM经营系统/deploy/Dockerfile'
REFRESH='E:/codex/project/MOSSDOOM经营系统/deploy/refresh-framework.sh'
HOST='migo@192.168.10.154'
BASE='https://192.168.10.154:8443'
SSHOPTS=(-i "$KEY" -o UserKnownHostsFile="$KNOWN" -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=3)

fail() { echo "BUILD_DEPLOY_FAILED: $*" >&2; exit 1; }

# 瞬时网络/SSH 失败自动重试；真正的命令错误直接抛出
rssh() {
  local n=1 out code
  while [ "$n" -le 4 ]; do
    if out=$(ssh "${SSHOPTS[@]}" "$HOST" "$@" 2>&1); then printf '%s\n' "$out"; return 0; fi
    code=$?
    if printf '%s' "$out" | grep -Eq 'Connection timed out|Connection refused|Connection reset|Broken pipe|kex_exchange|No route to host|Timeout, server'; then
      echo "   [SSH 重试 $n/4] $(printf '%s' "$out" | tail -1)" >&2
      sleep $((n * 8)); n=$((n + 1)); continue
    fi
    printf '%s\n' "$out" >&2; return "$code"
  done
  printf '%s\n' "$out" >&2; return 255
}

echo "=== 1. 前端构建 ==="
cd "$ROOT/frontend"
MOSS_FRONTEND_OUT_DIR="$FE" npm run build
EXPECT=$(grep -o 'index-[A-Za-z0-9_-]*\.js' "$FE/index.html" | head -1)
[ -n "$EXPECT" ] || fail "前端产物 index.html 里没有 index-*.js"
echo "   期望版本: $EXPECT"

echo "=== 2. 后端单测 + 打包 ==="
cd "$ROOT/backend"
bash "$ROOT/deploy/build-sixpiece-2.sh"

echo "=== 3. 部署前健康 ==="
rssh 'sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || echo HEALTH_FAIL'

echo "=== 4. 上传并重建 ==="
rssh 'sudo mkdir -p /tmp/mossdoom-framework-bundle && sudo chown migo:migo /tmp/mossdoom-framework-bundle'
scp -i "$KEY" -o UserKnownHostsFile="$KNOWN" -o StrictHostKeyChecking=yes "$JAR" "$HOST:/tmp/mossdoom-framework-bundle/app.jar"
scp -i "$KEY" -o UserKnownHostsFile="$KNOWN" -o StrictHostKeyChecking=yes "$DOCKERFILE" "$HOST:/tmp/mossdoom-framework-bundle/Dockerfile"
scp -i "$KEY" -o UserKnownHostsFile="$KNOWN" -o StrictHostKeyChecking=yes "$REFRESH" "$HOST:/tmp/mossdoom-framework-bundle/refresh-framework.sh"
rssh 'sudo bash /tmp/mossdoom-framework-bundle/refresh-framework.sh' | tail -30

echo "=== 5. 等待 UP（最多 3 轮 × 120s）==="
READY=0
for round in 1 2 3; do
  echo "   第 $round 轮…"
  out=$(rssh 'for i in $(seq 1 60); do h=$(sudo docker exec mossdoom-test-web-1 wget -qO- http://app:8080/actuator/health 2>/dev/null || true); printf "%s" "$h" | grep -q "status.:.UP" && { echo READY; exit 0; }; sleep 2; done; echo NOT_READY; exit 3' || true)
  if printf '%s' "$out" | grep -q READY; then READY=1; break; fi
  echo "   未就绪，20s 后重试" >&2; sleep 20
done
if [ "$READY" != 1 ]; then
  echo "--- 最近 60 行 app 日志 ---" >&2
  rssh 'sudo docker logs --tail 60 mossdoom-test-app-1' >&2 || true
  fail "健康检查未达到 UP"
fi
echo "   READY"

echo "=== 6. 验证页面版本 ==="
ACTUAL=$(rssh "curl -sk $BASE/ | grep -o 'index-[A-Za-z0-9_-]*\\.js' | head -1")
echo "   线上版本: $ACTUAL"
[ "$ACTUAL" = "$EXPECT" ] || fail "线上前端版本与本次构建不一致（期望 $EXPECT）"
rssh 'echo -n "favicon.ico: "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.ico; echo -n "favicon.svg: "; curl -sk -o /dev/null -w "%{http_code}\n" https://192.168.10.154:8443/favicon.svg'

echo "BUILD_DEPLOY_DONE $EXPECT"

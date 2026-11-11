#!/usr/bin/env bash
# 六件套框架（熔断+缓存+降级补齐）部署脚本。Run with sudo on the test host.
# 流程与 install-gateway-queries.sh 一致：备份 → 复制当前发布 → 构建新镜像（含新 app.jar）→ 更新 APP_IMAGE → 起 app → 切 current。
set -euo pipefail
test "$(id -u)" -eq 0
bundle=/tmp/mossdoom-framework-bundle
previous=$(readlink -f /opt/mossdoom/current)
release=/opt/mossdoom/releases/20261111-sixpiece-framework
test ! -e "$release" || { echo 'Release already exists; inspect instead of overwriting'; exit 1; }
cd "$previous"
bash backup.sh
mkdir -p "$release"
cp -a "$previous/." "$release/"
printf '%s\n' "$previous" > "$release/previous-release"
cp "$bundle/install-sixpiece-framework.sh" "$release/"
context=/opt/mossdoom/build/20261111-sixpiece-framework
mkdir -p "$context"
cp "$bundle/app.jar" "$bundle/Dockerfile" "$context/"
docker build --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 -t mossdoom/platform:sixpiece-framework "$context"
python3 - "$release" <<'PY'
import pathlib,sys,subprocess
release=pathlib.Path(sys.argv[1]);env=release/'.env'
text='\n'.join(x for x in env.read_text().splitlines() if not x.startswith('APP_IMAGE='))
text+='\nAPP_IMAGE='+subprocess.check_output(['docker','image','inspect','mossdoom/platform:sixpiece-framework','--format','{{.Id}}'],text=True);env.write_text(text)
PY
cd "$release"
docker compose config -q
docker compose up -d app
ready=false
for attempt in $(seq 1 40); do
  if docker compose exec -T web wget -qO- http://app:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then ready=true;break;fi
  sleep 2
done
test "$ready" = true
ln -s "$release" /opt/mossdoom/current.next
mv -Tf /opt/mossdoom/current.next /opt/mossdoom/current
echo 'SIXPIECE_FRAMEWORK_DEPLOYED'

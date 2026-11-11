#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" -eq 0
bundle=/tmp/mossdoom-framework-bundle
previous=$(readlink -f /opt/mossdoom/current)
release=/opt/mossdoom/releases/20261111-framework-03
test ! -e "$release"
cd "$previous"
bash backup.sh
mkdir -p "$release"
cp -a "$previous/." "$release/"
printf '%s\n' "$previous" > "$release/previous-release"
cp "$bundle/install-signed-queries.sh" "$release/"
context=/opt/mossdoom/build/20261111-signed-queries
mkdir -p "$context"
cp "$bundle/app.jar" "$bundle/Dockerfile" "$context/"
docker build --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 -t mossdoom/platform:signed-queries-01 "$context"
python3 - "$release" <<'PY'
import pathlib,json,sys,subprocess
release=pathlib.Path(sys.argv[1]);env=release/'.env'
text='\n'.join(x for x in env.read_text().splitlines() if not x.startswith('APP_IMAGE='))
text+='\nAPP_IMAGE='+subprocess.check_output(['docker','image','inspect','mossdoom/platform:signed-queries-01','--format','{{.Id}}'],text=True);env.write_text(text)
path=pathlib.Path('/etc/mossdoom/secrets/connector-targets.json');targets=json.loads(path.read_text())
for item in targets['targets']:
    if item['origin']=='https://dm-scm-test.emigo.cn':
        item['endpoint_ids']=['xinwuyun.produce-orders'];item['name']='新物云 TEST'
path.write_text(json.dumps(targets,ensure_ascii=False,indent=2))
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
echo 'SIGNED_QUERIES_DEPLOYED'

#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" -eq 0
bundle=/tmp/mossdoom-framework-bundle
previous=$(readlink -f /opt/mossdoom/current)
release=/opt/mossdoom/releases/20261110-framework-02
test ! -e "$release"
python3 - <<'PY'
import pathlib,secrets,json
root=pathlib.Path('/etc/mossdoom/secrets')
key=root/'connector_key'
if not key.exists():key.write_bytes(secrets.token_bytes(32));key.chmod(0o644)
targets=root/'connector-targets.json'
assert not targets.exists(),'Do not overwrite an existing target configuration'
targets.write_text(json.dumps({'targets':[
 {'name':'WMS TEST','origin':'http://wms-api-test.emigo.it','endpoint_ids':['wms.sku-stock']},
 {'name':'微服务 TEST（查询接口待核实认证后扩充）','origin':'http://gateway-test.emigo.it','endpoint_ids':[]},
 {'name':'新物云 TEST（等待签名资料）','origin':'https://dm-scm-test.emigo.cn','endpoint_ids':[]}
]},ensure_ascii=False,indent=2));targets.chmod(0o644)
PY
cd "$previous"
bash backup.sh
mkdir -p "$release"
cp -a "$previous/." "$release/"
printf '%s\n' "$previous" > "$release/previous-release"
cp "$bundle/install-collection-config.sh" "$release/install-collection-config.sh"
docker build --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 -t mossdoom/platform:collection-config "$bundle"
python3 - "$release" <<'PY'
import pathlib,sys,subprocess
release=pathlib.Path(sys.argv[1]);p=release/'compose.yaml';s=p.read_text()
needle='      - /etc/mossdoom/secrets/s3-ingestion-client.json:/run/secrets/s3_client.json:ro'
assert needle in s
s=s.replace(needle,needle+'\n      - /etc/mossdoom/secrets/connector_key:/run/secrets/connector_key:ro\n      - /etc/mossdoom/secrets/connector-targets.json:/run/secrets/connector_targets:ro')
p.write_text(s)
env=release/'.env';text='\n'.join(x for x in env.read_text().splitlines() if not x.startswith('APP_IMAGE='))
text+='\nAPP_IMAGE='+subprocess.check_output(['docker','image','inspect','mossdoom/platform:collection-config','--format','{{.Id}}'],text=True);env.write_text(text)
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
echo 'COLLECTION_CONFIGURATION_DEPLOYED'

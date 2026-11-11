#!/usr/bin/env bash
# Run with sudo on the designated test host. Existing infrastructure is retained.
set -euo pipefail
test "$(id -u)" -eq 0
test "$(hostname -I)" != ''
bundle=$(cd "$(dirname "$0")" && pwd)
release=/opt/mossdoom/releases/20261110-framework-01
test ! -e "$release" || { echo 'Release already exists; inspect instead of overwriting'; exit 1; }
previous=$(readlink -f /opt/mossdoom/current)
cd "$previous"
bash backup.sh
mkdir -p "$release"
cp -a "$previous/." "$release/"
printf '%s\n' "$previous" > "$release/previous-release"
python3 - "$bundle" "$release" <<'PY'
import pathlib,sys,secrets
bundle,release=map(pathlib.Path,sys.argv[1:])
compose=(release/'compose.yaml').read_text()
assert '\nnetworks:\n' in compose and '\n  app:\n' not in compose
compose=compose.replace('\nnetworks:\n','\n'+(bundle/'app-service.yaml').read_text()+'\nnetworks:\n')
(release/'compose.yaml').write_text(compose)
for name in ['app_admin_password','app_viewer_password']:
    path=pathlib.Path('/etc/mossdoom/secrets')/name
    if not path.exists():
        path.write_text(secrets.token_urlsafe(30)+'\n');path.chmod(0o644)
PY
cp "$bundle/nginx.conf" "$release/nginx.conf"
cp "$bundle/backup.sh" "$release/backup.sh"
chmod 644 /etc/mossdoom/secrets/s3-ingestion-client.json
docker build --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 -t mossdoom/platform:0.1.0 "$bundle"
docker image inspect mossdoom/platform:0.1.0 --format 'APP_IMAGE={{.Id}}' >> "$release/.env"
cat >> "$release/prometheus.yml" <<'YAML'
  - job_name: mossdoom-application
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['app:8080']
YAML
cd "$release"
docker compose config -q
docker compose up -d app
for attempt in $(seq 1 60); do
  if docker compose exec -T web wget -qO- http://app:8080/actuator/health | grep -q '"status":"UP"'; then break; fi
  sleep 2
done
docker compose exec -T web wget -qO- http://app:8080/actuator/health | grep -q '"status":"UP"'
docker compose up -d --force-recreate web prometheus
ln -s "$release" /opt/mossdoom/current.next
mv -Tf /opt/mossdoom/current.next /opt/mossdoom/current
echo 'FRAMEWORK_DEPLOYED https://192.168.10.154:8443/'

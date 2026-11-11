#!/usr/bin/env bash
# Local, consistent backup. No automatic deletion/retention cleanup.
set -euo pipefail
test "$(id -u)" -eq 0 || { echo 'Run with sudo'; exit 1; }
cd "$(dirname "$(readlink -f "$0")")"
umask 077
exec 9>/run/lock/mossdoom-backup.lock
flock -n 9 || { echo 'Backup already running'; exit 1; }
stamp=$(TZ=Asia/Shanghai date +%Y%m%d-%H%M%S)
target="/srv/mossdoom/backups/$stamp"
mkdir -m 700 "$target"
df -Pk /srv/mossdoom | awk 'NR==2 {if ($4 < 2097152) exit 1}' || { echo 'Need at least 2GiB free before backup'; exit 1; }
restart_services() {
  docker compose start objectstore >/dev/null
  for attempt in $(seq 1 60); do
    if docker compose exec -T web wget -qO- http://objectstore:8333/ >/dev/null 2>&1; then break; fi
    # S3 root may return 403 with valid authentication enabled; a TCP listener is sufficient here.
    if timeout 1 bash -c 'echo > /dev/tcp/127.0.0.1/8333' 2>/dev/null; then break; fi
    sleep 1
  done
  docker compose start app >/dev/null
}
trap restart_services EXIT
docker compose stop app
docker compose exec -T postgres pg_dumpall -U moss_admin --globals-only > "$target/roles.sql"
for db in moss_ingestion moss_business; do
  docker compose exec -T postgres pg_dump -U moss_admin -d "$db" -Fc > "$target/$db.dump"
done
docker compose stop objectstore

tar --sparse -C /srv/mossdoom -czf "$target/objectstore.tar.gz" objectstore
docker compose start objectstore >/dev/null
restart_services
trap - EXIT
tar -C /etc -czf "$target/secrets-tls.tar.gz" mossdoom
tar -czf "$target/deployment.tar.gz" --exclude='*.tar.gz' --exclude='*.dump' .
docker compose images --format json > "$target/images.json"
for image in $(docker compose config --images); do docker image inspect "$image" --format '{{.Id}} {{json .RepoDigests}}'; done > "$target/image-lock.txt"
(cd "$target" && sha256sum roles.sql *.dump *.tar.gz images.json image-lock.txt > SHA256SUMS)
printf 'mossdoom_last_backup_timestamp_seconds %s\n' "$(date +%s)" > /srv/mossdoom/metrics/backup.prom.tmp
mv /srv/mossdoom/metrics/backup.prom.tmp /srv/mossdoom/metrics/backup.prom
printf '%s\n' "$target" > /srv/mossdoom/latest-backup-path
echo "BACKUP_OK $target"

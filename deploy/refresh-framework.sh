#!/usr/bin/env bash
set -euo pipefail
bundle=/tmp/mossdoom-framework-bundle
cd "$bundle"
docker build --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474 -t mossdoom/platform:0.1.0 .
release=$(readlink -f /opt/mossdoom/current)
python3 - "$release" <<'PY'
import pathlib,sys,subprocess
p=pathlib.Path(sys.argv[1])/'.env'
s='\n'.join(x for x in p.read_text().splitlines() if not x.startswith('APP_IMAGE='))
s+='\nAPP_IMAGE='+subprocess.check_output(['docker','image','inspect','mossdoom/platform:0.1.0','--format','{{.Id}}'],text=True)
p.write_text(s)
PY
cd "$release"
docker compose up -d app

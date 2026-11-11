#!/usr/bin/env bash
set -e
export JAVA_HOME='G:\app\java\jdk21'
MVN='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/apache-maven-3.9.12/bin/mvn.cmd'
REPO='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/m2'
TARGET='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/backend-target'
FE='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/frontend-dist'

echo "=== backend unit tests ==="
MSYS_NO_PATHCONV=1 "$MVN" -o test -Dmaven.repo.local="$REPO" -Dmoss.build.directory="$TARGET" -Dmoss.frontend.directory="$TARGET/frontend-missing" -Dskip.frontend

echo "=== package jar (frontend static from FE) ==="
MSYS_NO_PATHCONV=1 "$MVN" -o package -DskipTests -Dmaven.repo.local="$REPO" -Dmoss.build.directory="$TARGET" -Dmoss.frontend.directory="$FE"

echo "=== jar info ==="
ls -l "$TARGET"/mossdoom-platform-*.jar
echo "BACKEND_BUILD_DONE"

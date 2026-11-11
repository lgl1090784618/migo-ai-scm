#!/usr/bin/env bash
set -e
cd "E:/codex/project/MOSSDOOM经营系统/frontend"
export MOSS_FRONTEND_OUT_DIR='E:/codex/data/tasks/MOSSDOOM经营系统/2026-11-09-全链路经营系统架构/work/framework/frontend-dist'
echo "=== npm build ==="
npm run build
echo "=== dist contents ==="
ls -la "$MOSS_FRONTEND_OUT_DIR"
echo "FRONTEND_BUILD_DONE"

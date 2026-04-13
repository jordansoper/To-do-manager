#!/usr/bin/env bash
# Run as root (via sudo from the app user). Pulls the git repo and re-runs install.sh.
set -euo pipefail

LOG="${TODO_DEPLOY_LOG:-/var/log/todo-manager-deploy.log}"
exec >>"$LOG" 2>&1

echo "=== deploy start $(date -Iseconds) ==="

REPO_DIR="${1:-/tmp/todo-manager}"
cd "$REPO_DIR"

if [[ ! -d .git ]]; then
  echo "ERROR: $REPO_DIR is not a git clone (.git missing). Clone the repo there or set TODO_UPDATE_REPO_DIR."
  exit 1
fi

git pull --ff-only
bash ./install.sh

echo "=== deploy done $(date -Iseconds) ==="

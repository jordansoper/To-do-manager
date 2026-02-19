#!/usr/bin/env bash
set -euo pipefail

SERVICE_NAME="todo-manager"
APP_DIR="/opt/todo-manager"
APP_USER="todo"

echo "==> Stopping service..."
systemctl stop "$SERVICE_NAME" 2>/dev/null || true
systemctl disable "$SERVICE_NAME" 2>/dev/null || true

echo "==> Removing systemd service..."
rm -f /etc/systemd/system/${SERVICE_NAME}.service
systemctl daemon-reload

echo "==> Removing application files..."
rm -rf "$APP_DIR"

echo "==> Removing application user..."
userdel "$APP_USER" 2>/dev/null || true

echo "==> Done. To-Do Manager has been uninstalled."

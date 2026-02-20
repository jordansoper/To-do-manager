#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# To-Do Manager - Install Script
# Designed for Debian/Ubuntu-based LXC containers (Proxmox)
# ============================================================================

APP_DIR="/opt/todo-manager"
APP_USER="todo"
SERVICE_NAME="todo-manager"
REPO_URL="https://github.com/jordansoper/To-do-manager.git"
BRANCH="${1:-main}"

echo "==> Installing system dependencies..."
apt-get update -qq
apt-get install -y -qq python3 python3-venv python3-pip git

echo "==> Creating application user..."
if ! id "$APP_USER" &>/dev/null; then
    useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
fi

echo "==> Setting up application directory..."
if [ -d "$APP_DIR/.git" ]; then
    echo "==> Git repo already exists, pulling latest from branch: $BRANCH..."
    git -C "$APP_DIR" fetch origin
    git -C "$APP_DIR" checkout "$BRANCH"
    git -C "$APP_DIR" pull origin "$BRANCH"
else
    echo "==> Cloning repository (branch: $BRANCH)..."
    git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi

echo "==> Creating Python virtual environment..."
python3 -m venv "$APP_DIR/venv"
"$APP_DIR/venv/bin/pip" install --quiet --upgrade pip
"$APP_DIR/venv/bin/pip" install --quiet -r "$APP_DIR/requirements.txt"

echo "==> Setting permissions..."
mkdir -p "$APP_DIR/instance"
chown -R "$APP_USER":"$APP_USER" "$APP_DIR"

echo "==> Installing systemd service..."
cat > /etc/systemd/system/${SERVICE_NAME}.service <<EOF
[Unit]
Description=To-Do Manager
After=network.target

[Service]
Type=exec
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}
ExecStart=${APP_DIR}/venv/bin/gunicorn --bind 0.0.0.0:5000 --workers 2 app:app
Restart=on-failure
RestartSec=5

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=${APP_DIR}/instance
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl start "$SERVICE_NAME"

echo ""
echo "============================================"
echo "  To-Do Manager installed successfully!"
echo "  Access at: http://$(hostname -I | awk '{print $1}'):5000"
echo "  Service:   systemctl status $SERVICE_NAME"
echo "  Logs:      journalctl -u $SERVICE_NAME -f"
echo "============================================"

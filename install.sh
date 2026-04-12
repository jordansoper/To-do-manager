#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# To-Do Manager - Install Script
# Designed for Debian/Ubuntu-based LXC containers (Proxmox)
# ============================================================================

APP_DIR="/opt/todo-manager"
APP_USER="todo"
SERVICE_NAME="todo-manager"

echo "==> Installing system dependencies..."
apt-get update -qq
apt-get install -y -qq python3 python3-venv python3-pip git

echo "==> Creating application user..."
if ! id "$APP_USER" &>/dev/null; then
    useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
fi

echo "==> Setting up application directory..."
if [[ ! -f templates/index.html ]]; then
    echo "ERROR: templates/index.html not found. Run install.sh from the repo root (where app.py and templates/ live)."
    exit 1
fi
mkdir -p "$APP_DIR"
cp app.py requirements.txt "$APP_DIR/"
cp -r templates "$APP_DIR/"
if [[ -d static ]]; then
    cp -r static "$APP_DIR/"
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
# Single worker: SQLite does not tolerate multiple writer processes well ("database is locked").
ExecStart=${APP_DIR}/venv/bin/gunicorn --bind 0.0.0.0:5000 --workers 1 --threads 4 app:app
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

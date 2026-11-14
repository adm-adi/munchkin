#!/bin/bash
# Munchkin Server Setup Script (Debian / Ubuntu)
# Run as root: bash setup.sh

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: setup.sh must be run as root." >&2
    exit 1
fi

echo "Installing prerequisites and Node.js 20 APT repository..."
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg sqlite3 openssl

install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
    | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
chmod 0644 /etc/apt/keyrings/nodesource.gpg
echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" \
    > /etc/apt/sources.list.d/nodesource.list

apt-get update
apt-get install -y --no-install-recommends nodejs

echo "Creating unprivileged service account..."
if ! id -u munchkin >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin munchkin
fi

echo "Creating application, data, backup, log, and config directories..."
install -d -m 0755 -o root -g munchkin /opt/munchkin-server
install -d -m 0750 -o munchkin -g munchkin /var/lib/munchkin /var/lib/munchkin/backups /opt/munchkin-server/logs
install -d -m 0750 -o root -g munchkin /etc/munchkin

ENV_FILE="/etc/munchkin/server.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "Generating $ENV_FILE..."
    (
        umask 077
        JWT_SECRET="$(openssl rand -hex 48)"
        cat > "$ENV_FILE" <<EOF
NODE_ENV=production
PORT=8765
JWT_SECRET=${JWT_SECRET}
MUNCHKIN_DB_PATH=/var/lib/munchkin/munchkin.db
MUNCHKIN_LOG_DIR=/opt/munchkin-server/logs
MUNCHKIN_BACKUP_DIR=/var/lib/munchkin/backups
MUNCHKIN_TRUST_PROXY=0
EOF
    )
elif ! grep -q '^JWT_SECRET=' "$ENV_FILE"; then
    echo "Adding missing JWT_SECRET to $ENV_FILE..."
    (
        umask 077
        printf 'JWT_SECRET=%s\n' "$(openssl rand -hex 48)" >> "$ENV_FILE"
    )
fi
chown root:root "$ENV_FILE"
chmod 0600 "$ENV_FILE"

if [ ! -f /opt/munchkin-server/package-lock.json ] || [ ! -f /opt/munchkin-server/server.js ]; then
    echo "Error: deploy server files into /opt/munchkin-server before running setup.sh." >&2
    exit 1
fi

echo "Installing production npm dependencies..."
cd /opt/munchkin-server
npm ci --omit=dev

echo "Installing hardened systemd unit..."
cat > /etc/systemd/system/munchkin.service <<'EOF'
[Unit]
Description=Munchkin Tracker WebSocket Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=munchkin
Group=munchkin
WorkingDirectory=/opt/munchkin-server
EnvironmentFile=/etc/munchkin/server.env
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/munchkin /opt/munchkin-server/logs
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
EOF

echo "Enabling and starting munchkin service..."
systemctl daemon-reload
systemctl enable munchkin
systemctl restart munchkin

# Firewall / TLS note:
# - Terminate TLS via a reverse proxy (Caddy/Nginx) on :443 forwarding WebSockets
#   to 127.0.0.1:8765 (and set MUNCHKIN_TRUST_PROXY=1 in /etc/munchkin/server.env),
#   OR configure MUNCHKIN_TLS_KEY / MUNCHKIN_TLS_CERT directly on :8765.
if command -v ufw >/dev/null 2>&1; then
    echo "Configuring UFW rules (443/tcp for reverse-proxy TLS, 8765/tcp if direct WSS)..."
    ufw allow 443/tcp || true
    ufw allow 8765/tcp || true
fi

echo ""
echo "Munchkin Server installed!"
echo "Check status: systemctl status munchkin"
echo "View logs:    journalctl -u munchkin -f"
echo "TLS proxy:    Terminate HTTPS/WSS on :443 -> 127.0.0.1:8765, or set MUNCHKIN_TLS_KEY/MUNCHKIN_TLS_CERT"
echo "Server URL:   wss://munchking-sirpepo.duckdns.org:8765"

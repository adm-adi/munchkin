#!/bin/bash
# Munchkin Server Setup Script for Hetzner cx23
# Run as root: bash setup.sh

set -e

echo "🚀 Installing Munchkin Server..."

# Install Node.js 20
echo "📦 Installing Node.js..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs

# Create app directory
echo "📁 Creating directories..."
mkdir -p /opt/munchkin-server
cd /opt/munchkin-server

# Copy files (run this after scp)
echo "📄 Files should be in /opt/munchkin-server"

# Install dependencies
echo "📦 Installing npm dependencies..."
npm install

# Create systemd service
echo "⚙️ Creating systemd service..."
cat > /etc/systemd/system/munchkin.service << 'EOF'
[Unit]
Description=Munchkin Tracker WebSocket Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/munchkin-server
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=5
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
echo "🔧 Enabling service..."
systemctl daemon-reload
systemctl enable munchkin
systemctl start munchkin

# Open firewall
echo "🔥 Opening port 8765..."
ufw allow 8765/tcp

echo ""
echo "✅ Munchkin Server installed!"
echo ""
echo "📊 Check status: systemctl status munchkin"
echo "📜 View logs: journalctl -u munchkin -f"
echo "🔌 Server URL: ws://23.88.48.58:8765"

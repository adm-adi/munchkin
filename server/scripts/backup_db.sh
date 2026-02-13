#!/bin/bash
# Simple SQLite Backup Script
# Usage: ./backup_db.sh via cron

DB_PATH="/opt/munchkin-server/server/munchkin.db"
BACKUP_DIR="/opt/munchkin-server/backups"
DATE=$(date +%Y-%m-%d_%H-%M-%S)

mkdir -p "$BACKUP_DIR"

# Use sqlite3 .backup command for safe hot backup
sqlite3 "$DB_PATH" ".backup '$BACKUP_DIR/munchkin_$DATE.db'"

# Keep only last 7 days
find "$BACKUP_DIR" -name "munchkin_*.db" -mtime +7 -delete

echo "Backup created: $BACKUP_DIR/munchkin_$DATE.db"

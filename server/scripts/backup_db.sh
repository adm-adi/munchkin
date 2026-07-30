#!/bin/bash
# Simple SQLite Backup Script
# Usage: ./backup_db.sh via cron
#
# Paths follow the same environment variables the server itself honours, so the
# backup keeps working after the database moved out of the code tree (which it
# had to, for the service to run as an unprivileged user that cannot write to
# its own source).
set -euo pipefail

DB_PATH="${MUNCHKIN_DB_PATH:-/var/lib/munchkin/munchkin.db}"
BACKUP_DIR="${MUNCHKIN_BACKUP_DIR:-/var/lib/munchkin/backups}"
DATE=$(date +%Y-%m-%d_%H-%M-%S)

if [ ! -f "$DB_PATH" ]; then
    echo "Backup skipped: no database at $DB_PATH" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# Use sqlite3 .backup command for safe hot backup
sqlite3 "$DB_PATH" ".backup '$BACKUP_DIR/munchkin_$DATE.db'"

# Keep only last 7 days
find "$BACKUP_DIR" -name "munchkin_*.db" -mtime +7 -delete

echo "Backup created: $BACKUP_DIR/munchkin_$DATE.db"

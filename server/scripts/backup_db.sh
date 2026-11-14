#!/bin/bash
# SQLite Hot Backup Script (with optional age or OpenSSL AES-256-CBC encryption)
# Usage: ./backup_db.sh (e.g. via cron or systemd timer)

set -euo pipefail
umask 077

DB_PATH="${MUNCHKIN_DB_PATH:-/var/lib/munchkin/munchkin.db}"
BACKUP_DIR="${MUNCHKIN_BACKUP_DIR:-/var/lib/munchkin/backups}"
DATE="$(date +%Y-%m-%d_%H-%M-%S)"
BACKUP_FILE="$BACKUP_DIR/munchkin_${DATE}.db"

if [ ! -f "$DB_PATH" ]; then
    echo "Backup skipped: no database at $DB_PATH" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"

# Safe hot backup using SQLite backup API
sqlite3 "$DB_PATH" ".backup '$BACKUP_FILE'"
chmod 0600 "$BACKUP_FILE"

FINAL_BACKUP="$BACKUP_FILE"

if [ -n "${MUNCHKIN_BACKUP_AGE_RECIPIENT:-}" ]; then
    ENC_FILE="${BACKUP_FILE}.age"
    if ! age -r "$MUNCHKIN_BACKUP_AGE_RECIPIENT" -o "$ENC_FILE" "$BACKUP_FILE"; then
        rm -f "$ENC_FILE" "$BACKUP_FILE"
        echo "Error: age encryption failed" >&2
        exit 1
    fi
    chmod 0600 "$ENC_FILE"
    rm -f "$BACKUP_FILE"
    FINAL_BACKUP="$ENC_FILE"
elif [ -n "${MUNCHKIN_BACKUP_PASSPHRASE:-}" ]; then
    ENC_FILE="${BACKUP_FILE}.enc"
    if ! openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
        -pass env:MUNCHKIN_BACKUP_PASSPHRASE \
        -in "$BACKUP_FILE" -out "$ENC_FILE"; then
        rm -f "$ENC_FILE" "$BACKUP_FILE"
        echo "Error: openssl encryption failed" >&2
        exit 1
    fi
    chmod 0600 "$ENC_FILE"
    rm -f "$BACKUP_FILE"
    FINAL_BACKUP="$ENC_FILE"
fi

# Prune backups older than 7 days
find "$BACKUP_DIR" -maxdepth 1 -type f -name "munchkin_*.db*" -mtime +7 -delete

echo "Backup created: $FINAL_BACKUP"

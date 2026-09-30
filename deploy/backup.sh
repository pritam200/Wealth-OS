#!/usr/bin/env bash
# Nightly database backup: compressed pg_dump into deploy/backups, keeping the last 14 days.
# Installed as a cron job by DEPLOY.md step 7. Restore instructions are in DEPLOY.md.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

mkdir -p backups
chmod 700 backups
file="backups/marketai-$(date +%Y%m%d-%H%M).sql.gz"

docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists | gzip > "$file"

# A dump that is suspiciously small is a failed dump, not a backup.
if [ "$(stat -c %s "$file")" -lt 1024 ]; then
  echo "Backup $file is too small — pg_dump probably failed" >&2
  exit 1
fi

find backups -name 'marketai-*.sql.gz' -mtime +14 -delete
echo "Backup written: $file"

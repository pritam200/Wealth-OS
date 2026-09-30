#!/usr/bin/env bash
# Keeps the DuckDNS name pointing at this server's public IP. Run every 5 minutes from cron.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
sub="${DOMAIN%.duckdns.org}"
curl -fsS "https://www.duckdns.org/update?domains=${sub}&token=${DUCKDNS_TOKEN}&ip=" -o /dev/null

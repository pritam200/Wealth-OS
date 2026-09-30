#!/usr/bin/env bash
# Creates deploy/.env from .env.example (if missing) and fills every empty generated secret.
# Safe to re-run: existing values are never overwritten.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || cp .env.example .env
chmod 600 .env

fill() {  # fill KEY VALUE — only if KEY is currently empty
  local key="$1" value="$2"
  if grep -qE "^${key}=$" .env; then
    # '|' as the sed delimiter: base64 values can contain '/'.
    sed -i.bak "s|^${key}=$|${key}=${value}|" .env && rm -f .env.bak
    echo "generated ${key}"
  fi
}

fill DB_PASSWORD "$(openssl rand -hex 24)"
fill REDIS_PASSWORD "$(openssl rand -hex 24)"
fill JWT_SECRET "$(openssl rand -base64 64 | tr -d '\n')"
fill PDF_PASSWORD_ENC_KEY "$(openssl rand -base64 32 | tr -d '\n')"

echo
echo "Now edit deploy/.env and fill: DOMAIN, DUCKDNS_TOKEN, SIGNUP_ALLOWED_EMAILS,"
echo "GMAIL_CLIENT_ID/SECRET, MAIL_*, GEMINI_API_KEY."
echo "Keep a copy of JWT_SECRET and PDF_PASSWORD_ENC_KEY somewhere safe (a password manager)."

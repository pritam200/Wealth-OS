#!/usr/bin/env bash
# Verifies GEMINI_API_KEY actually authenticates against the Generative Language API and gets
# a real response, without pulling in the Python google-genai SDK (this repo is 100% Java/TS —
# no Python runtime dependency anywhere else in it) and without ever printing the key itself.
#
# Usage:
#   export GEMINI_API_KEY=...        # or have it in .env at the repo root
#   ./scripts/test_gemini_connection.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "${GEMINI_API_KEY:-}" ] && [ -f "$REPO_ROOT/.env" ]; then
  # Only pull this one variable out of .env — never source the whole file blindly.
  GEMINI_API_KEY="$(grep -E '^GEMINI_API_KEY=' "$REPO_ROOT/.env" | tail -1 | cut -d '=' -f2-)"
fi

if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "Error: GEMINI_API_KEY is not set (checked the environment and .env)." >&2
  exit 1
fi

MODEL="${GEMINI_MODEL:-gemini-2.0-flash}"
URL="https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent"

HTTP_STATUS=$(curl -sS -o /tmp/gemini_test_response.json -w "%{http_code}" \
  -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: ${GEMINI_API_KEY}" \
  -d '{"contents":[{"parts":[{"text":"Say '"'"'Gemini API connection successful!'"'"'"}]}]}')

if [ "$HTTP_STATUS" != "200" ]; then
  echo "Gemini API connection failed (HTTP $HTTP_STATUS):"
  cat /tmp/gemini_test_response.json
  rm -f /tmp/gemini_test_response.json
  exit 1
fi

echo "Connection test passed:"
grep -o '"text": *"[^"]*"' /tmp/gemini_test_response.json | head -1
rm -f /tmp/gemini_test_response.json

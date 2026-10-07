#!/bin/sh
# One-command production deploy: pull, decrypt secrets into a temporary folder, rebuild and
# restart the stack, and always remove the plaintext again.
#
#   DEPLOY_SECRET_KEY='<passphrase>' ./deploy.sh
#
# DEPLOY_SECRET_KEY must already be in the environment (CI secret, or exported/loaded by your
# process manager on the server). It is never read from a file in the repo and never printed.
set -eu
umask 077
cd "$(dirname "$0")"

# Registered first so the decrypted files are destroyed whether the deploy succeeds or fails.
trap './scripts/secrets.sh clean' EXIT INT TERM

# SKIP_PULL=1 for a local run of the very same stack (nothing else about it changes).
[ "${SKIP_PULL:-}" = 1 ] || git pull --ff-only

./scripts/secrets.sh decrypt-auto

# --env-file feeds ${DOMAIN}, ${DB_PASSWORD}... into the compose file itself (postgres, redis,
# caddy); env_file inside the file feeds the same values into the backend container.
docker compose -f deploy/docker-compose.prod.yml --env-file .tmp-secrets/deploy.env \
  up -d --build --remove-orphans

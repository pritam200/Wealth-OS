#!/bin/bash
# Copy the Neon database into a Postgres on this Mac, so the app can run without Neon.
#
#   backend/tools/neon-to-local.sh          # set up local Postgres, copy Neon into it
#   backend/tools/neon-to-local.sh start    # just start the local Postgres (after a reboot)
#   backend/tools/neon-to-local.sh stop
#
# Neon is only read (pg_dump). The local server keeps its data in ~/marketai-pgdata and listens
# on localhost:5433 only; its connection settings are written to backend/local.env (git-ignored).
# Then start the backend with local.env instead of neon.env.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PGBIN="${PGBIN:-/opt/homebrew/opt/postgresql@17/bin}"
DATA="${PGDATA_LOCAL:-$HOME/marketai-pgdata}"
PORT=5433
DB=marketai_db
APP_USER=marketai

if [ ! -x "$PGBIN/pg_dump" ]; then
  echo "Postgres 17 tools not found. Install them with:  brew install postgresql@17" >&2
  exit 1
fi

start() {
  if ! "$PGBIN/pg_isready" -q -h localhost -p "$PORT"; then
    "$PGBIN/pg_ctl" -D "$DATA" -l "$DATA/server.log" -o "-p $PORT -k /tmp -c listen_addresses=localhost" -w start
  fi
}

case "${1:-copy}" in
  start) start; exit 0 ;;
  stop)  "$PGBIN/pg_ctl" -D "$DATA" stop; exit 0 ;;
  copy)  ;;
  *) echo "usage: $0 [copy|start|stop]" >&2; exit 1 ;;
esac

# 1. A local server, created once. The app's password is random and kept only in local.env.
if [ ! -f "$DATA/PG_VERSION" ]; then
  "$PGBIN/initdb" -D "$DATA" -U postgres --auth-local=trust --auth-host=scram-sha-256 >/dev/null
  echo "Created local Postgres in $DATA"
fi
if [ ! -f "$DIR/local.env" ]; then
  PW="$(openssl rand -hex 16)"
  umask 077
  cat > "$DIR/local.env" <<EOF
DB_HOST=localhost
DB_PORT=$PORT
DB_NAME=$DB
DB_USER=$APP_USER
DB_PASSWORD=$PW
DB_SSLMODE=disable
DB_CHANNEL_BINDING=disable
EOF
fi
start
set -a; source "$DIR/local.env"; set +a
LOCAL_PW="$DB_PASSWORD"
psql_local() { "$PGBIN/psql" -h /tmp -p "$PORT" -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
psql_local -d postgres -c "DO \$\$BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP_USER') THEN
    CREATE ROLE $APP_USER LOGIN PASSWORD '$LOCAL_PW';
  END IF; END\$\$;"

# 2. Dump Neon (read-only). The dump holds your financial data: it stays in backend/.run, owner-only.
set -a; source "$DIR/neon.env"; set +a
mkdir -p "$DIR/.run" && chmod 700 "$DIR/.run"
DUMP="$DIR/.run/neon-$(date +%Y%m%d-%H%M%S).dump"
echo "Dumping Neon (this can take a few minutes)..."
PGPASSWORD="$DB_PASSWORD" "$PGBIN/pg_dump" \
  "host=$DB_HOST port=${DB_PORT:-5432} dbname=$DB_NAME user=$DB_USER sslmode=require" \
  -Fc --no-owner --no-privileges -f "$DUMP"
chmod 600 "$DUMP"
echo "Dump saved: $DUMP"

# 3. Restore into a fresh local database owned by the app user. An existing local copy is renamed,
#    not dropped, so a second run can't lose anything.
if psql_local -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$DB'" | grep -q 1; then
  OLD="${DB}_before_$(date +%Y%m%d_%H%M%S)"
  psql_local -d postgres -c "ALTER DATABASE $DB RENAME TO $OLD"
  echo "Previous local copy kept as $OLD"
fi
psql_local -d postgres -c "CREATE DATABASE $DB OWNER $APP_USER"
PGPASSWORD="$LOCAL_PW" "$PGBIN/pg_restore" -h localhost -p "$PORT" -U "$APP_USER" -d "$DB" \
  --no-owner --no-privileges "$DUMP" \
  || echo "pg_restore reported errors above. Neon-only extensions failing is harmless; anything else, stop and check."
psql_local -d "$DB" -c "ANALYZE"

echo
echo "Done. Row counts of the biggest tables in the local copy:"
psql_local -d "$DB" -c "SELECT relname AS table, n_live_tup AS rows FROM pg_stat_user_tables ORDER BY n_live_tup DESC LIMIT 10"
echo
echo "Start the backend against it with:"
echo "  set -a && source ./local.env && source ./gmail.env && set +a && ./start.sh"

#!/usr/bin/env bash
#
# sql-interview-mastery — user-space PostgreSQL cluster manager
# -------------------------------------------------------------
# Runs a PostgreSQL cluster entirely inside the invoking user's home directory.
# No root, no sudo, no Docker, and no interference with a system PostgreSQL
# that may already be listening on 5432.
#
# This exists because Docker is not usable on every machine (an unreachable
# daemon socket is enough to stop `docker compose up`), and because the project
# needs a cluster where it can create its own roles — which a shared system
# cluster will not allow without administrative access.
#
# Usage:
#   bash scripts/db.sh init      # create the cluster (idempotent)
#   bash scripts/db.sh start     # start it
#   bash scripts/db.sh stop      # stop it
#   bash scripts/db.sh status    # is it running?
#   bash scripts/db.sh psql [db] # open a superuser shell
#   bash scripts/db.sh destroy   # delete the cluster and ALL its data
#
set -euo pipefail

PGPORT_DEFAULT=5433
PG_SUPERUSER="sqlmastery_admin"
BASE="${SQLMASTERY_PG_BASE:-$HOME/.local/opt/sqlmastery-data}"
DATA="$BASE/pgdata"
SOCKET_DIR="$BASE/run"
LOG="$BASE/log/postgres.log"
PORT="${SQLMASTERY_DB_PORT:-$PGPORT_DEFAULT}"

log()  { printf '  %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# Locate the PostgreSQL server binaries. They are not on PATH on Debian and
# Ubuntu — only the client wrappers are — so look in the standard location.
find_bindir() {
  if command -v pg_ctl >/dev/null 2>&1 && command -v initdb >/dev/null 2>&1; then
    dirname "$(command -v pg_ctl)"
    return
  fi
  local candidate
  for candidate in /usr/lib/postgresql/*/bin /usr/pgsql-*/bin /opt/homebrew/opt/postgresql@*/bin; do
    [[ -x "$candidate/pg_ctl" ]] && { echo "$candidate"; return; }
  done
  die "Could not find pg_ctl/initdb. Install the PostgreSQL server package (postgresql-16 on Debian/Ubuntu)."
}

BIN="$(find_bindir)"
export PATH="$BIN:$PATH"

cmd_init() {
  if [[ -f "$DATA/PG_VERSION" ]]; then
    ok "Cluster already initialised at $DATA (PostgreSQL $(cat "$DATA/PG_VERSION"))"
    return
  fi
  mkdir -p "$DATA" "$SOCKET_DIR" "$(dirname "$LOG")"
  chmod 700 "$DATA"
  # --auth-local=trust: only this UNIX user can reach the socket, and the
  # socket lives under $HOME with default permissions. Host connections still
  # require scram-sha-256, which is what the application uses.
  initdb -D "$DATA" -U "$PG_SUPERUSER" \
         --auth-local=trust --auth-host=scram-sha-256 \
         -E UTF8 --locale=C.UTF-8 >/dev/null
  cat >> "$DATA/postgresql.conf" <<CONF

# ---- sql-interview-mastery local cluster -----------------------------------
port = $PORT
listen_addresses = '127.0.0.1'
unix_socket_directories = '$SOCKET_DIR'
shared_buffers = 128MB
max_connections = 50
# Surface anything pathological the playground manages to run.
log_min_duration_statement = 2000
CONF
  ok "Initialised cluster at $DATA on port $PORT"
}

cmd_start() {
  [[ -f "$DATA/PG_VERSION" ]] || die "No cluster yet. Run: bash scripts/db.sh init"
  if pg_ctl -D "$DATA" status >/dev/null 2>&1; then
    ok "Already running (port $PORT)"
    return
  fi
  mkdir -p "$SOCKET_DIR" "$(dirname "$LOG")"
  pg_ctl -D "$DATA" -l "$LOG" -w start >/dev/null
  ok "Started on 127.0.0.1:$PORT  (log: $LOG)"
}

cmd_stop() {
  if pg_ctl -D "$DATA" status >/dev/null 2>&1; then
    pg_ctl -D "$DATA" -m fast -w stop >/dev/null
    ok "Stopped"
  else
    log "Not running"
  fi
}

cmd_status() {
  if pg_ctl -D "$DATA" status >/dev/null 2>&1; then
    ok "Running on 127.0.0.1:$PORT"
    psql -h "$SOCKET_DIR" -p "$PORT" -U "$PG_SUPERUSER" -d postgres -qtAX \
         -c "select 'server_version=' || current_setting('server_version');"
  else
    warn "Not running"
    return 1
  fi
}

cmd_psql() {
  exec psql -h "$SOCKET_DIR" -p "$PORT" -U "$PG_SUPERUSER" -d "${1:-postgres}"
}

cmd_destroy() {
  warn "This deletes the cluster at $DATA and every byte of data in it."
  read -r -p "  Type the word 'destroy' to confirm: " answer </dev/tty
  [[ "$answer" == "destroy" ]] || die "Aborted."
  pg_ctl -D "$DATA" -m immediate -w stop >/dev/null 2>&1 || true
  rm -rf "$DATA"
  ok "Removed $DATA"
}

case "${1:-}" in
  init)    cmd_init ;;
  start)   cmd_init; cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_stop; cmd_start ;;
  status)  cmd_status ;;
  psql)    shift; cmd_psql "${1:-postgres}" ;;
  destroy) cmd_destroy ;;
  *)
    cat <<USAGE
  sql-interview-mastery :: local PostgreSQL cluster

    bash scripts/db.sh init       create the cluster (idempotent)
    bash scripts/db.sh start      start it
    bash scripts/db.sh stop       stop it
    bash scripts/db.sh restart    stop then start
    bash scripts/db.sh status     report whether it is running
    bash scripts/db.sh psql [db]  superuser psql shell
    bash scripts/db.sh destroy    delete the cluster and all its data

  Data directory : $DATA
  Socket         : $SOCKET_DIR
  Port           : $PORT
USAGE
    exit 1 ;;
esac

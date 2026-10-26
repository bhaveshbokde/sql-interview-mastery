#!/usr/bin/env bash
#
# sql-interview-mastery — database provisioning (idempotent, non-destructive)
# ---------------------------------------------------------------------------
# Creates exactly two roles and two databases:
#
#   role  sqlmastery_app      owns both schemas, runs Flyway, serves the API.
#                             NOSUPERUSER NOCREATEDB NOCREATEROLE.
#   role  sqlmastery_learner  the ONLY role that ever executes SQL typed into
#                             the playground. It is given CONNECT and nothing
#                             else here; the Flyway migrations grant it USAGE
#                             on the learning schema and SELECT on its tables.
#                             Its transactions default to READ ONLY at the
#                             role level, so a read-only transaction is not
#                             something the application can forget to request.
#
#   db    sqlmastery_db       the application database.
#   db    sqlmastery_test_db  used by the integration tests.
#
# It never drops or recreates anything. Existing roles and databases are left
# alone, except for a password rotation you confirm interactively.
#
# Passwords are generated from /dev/urandom, written only to .env (mode 0600),
# and passed to psql on stdin — never as a command-line argument, so they do
# not appear in `ps` output or in shell history.
#
# Usage:
#   bash scripts/provision-db.sh
#
set -euo pipefail

APP_ROLE="sqlmastery_app"
LEARNER_ROLE="sqlmastery_learner"
APP_DB="sqlmastery_db"
TEST_DB="sqlmastery_test_db"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

BASE="${SQLMASTERY_PG_BASE:-$HOME/.local/opt/sqlmastery-data}"
SOCKET_DIR="$BASE/run"
PORT="${SQLMASTERY_DB_PORT:-5433}"
HOST="${SQLMASTERY_DB_HOST:-127.0.0.1}"

log()  { printf '  %s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

echo
echo "sql-interview-mastery :: database provisioning"
echo "----------------------------------------------"

command -v psql >/dev/null || die "psql not found on PATH."

# ----------------------------------------------------------- superuser path --
# Three supported ways to reach a superuser, tried in order:
#   1. the bundled user-space cluster over its UNIX socket (trust auth)
#   2. an explicit superuser supplied in the environment (Docker Compose)
#   3. a local cluster that already trusts this OS user
SU_ARGS=()
if [[ -S "$SOCKET_DIR/.s.PGSQL.$PORT" ]]; then
  SU_ARGS=(-h "$SOCKET_DIR" -p "$PORT" -U sqlmastery_admin)
  log "Using the bundled user-space cluster at $SOCKET_DIR (port $PORT)"
elif [[ -n "${SQLMASTERY_PG_SUPERUSER:-}" ]]; then
  SU_ARGS=(-h "$HOST" -p "$PORT" -U "$SQLMASTERY_PG_SUPERUSER")
  export PGPASSWORD="${SQLMASTERY_PG_SUPERUSER_PASSWORD:-}"
  log "Using superuser '$SQLMASTERY_PG_SUPERUSER' at $HOST:$PORT"
else
  SU_ARGS=(-h "$HOST" -p "$PORT" -U postgres)
  log "Using superuser 'postgres' at $HOST:$PORT"
fi

psql_su() { psql "${SU_ARGS[@]}" -v ON_ERROR_STOP=1 -qtAX -d postgres; }
psql_su_db() { psql "${SU_ARGS[@]}" -v ON_ERROR_STOP=1 -qtAX -d "$1"; }

echo "SELECT 1;" | psql_su >/dev/null 2>&1 || die \
  "Cannot reach PostgreSQL as a superuser.
     Start the bundled cluster with:   bash scripts/db.sh start
     or point at another cluster with: SQLMASTERY_PG_SUPERUSER, SQLMASTERY_PG_SUPERUSER_PASSWORD,
                                       SQLMASTERY_DB_HOST, SQLMASTERY_DB_PORT"
ok "Connected as superuser ($(echo 'SELECT current_user;' | psql_su))"
log "Server version: $(echo 'SHOW server_version;' | psql_su)"

# --------------------------------------------------------------- passwords --
# Reuse whatever is already in .env so re-running the script is a no-op for an
# application that is already configured.
read_env() { [[ -f "$ENV_FILE" ]] && sed -n "s/^$1=//p" "$ENV_FILE" | head -n1 || true; }
gen_pw() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }

APP_PW="$(read_env SQLMASTERY_APP_PASSWORD)"
LEARNER_PW="$(read_env SQLMASTERY_LEARNER_PASSWORD)"
APP_PW_IS_NEW="no"; LEARNER_PW_IS_NEW="no"
if [[ -z "$APP_PW" || "$APP_PW" == replace-me* ]]; then APP_PW="$(gen_pw)"; APP_PW_IS_NEW="yes"; fi
if [[ -z "$LEARNER_PW" || "$LEARNER_PW" == replace-me* ]]; then LEARNER_PW="$(gen_pw)"; LEARNER_PW_IS_NEW="yes"; fi
[[ ${#APP_PW} -eq 32 && ${#LEARNER_PW} -eq 32 ]] || die "Failed to generate passwords."

# -------------------------------------------------------------------- roles --
role_exists() { echo "SELECT 1 FROM pg_roles WHERE rolname = '$1';" | psql_su; }

ensure_role() {
  local role="$1" pw="$2" is_new="$3"
  if [[ "$(role_exists "$role")" == "1" ]]; then
    if [[ "$is_new" == "yes" ]]; then
      echo
      read -r -p "  Role '$role' exists but .env has no password for it. Reset its password? [y/N] " a </dev/tty
      [[ "${a,,}" == "y" ]] || die "Aborted. Put the existing password into $ENV_FILE and re-run."
      printf "ALTER ROLE %s WITH LOGIN PASSWORD '%s';\n" "$role" "$pw" | psql_su >/dev/null
      ok "Password rotated for '$role'"
    else
      warn "Role '$role' already exists — left untouched."
    fi
  else
    printf "CREATE ROLE %s WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT PASSWORD '%s';\n" \
      "$role" "$pw" | psql_su >/dev/null
    ok "Created least-privilege role '$role'"
  fi
}

ensure_role "$APP_ROLE" "$APP_PW" "$APP_PW_IS_NEW"
ensure_role "$LEARNER_ROLE" "$LEARNER_PW" "$LEARNER_PW_IS_NEW"

# The learner's safety rails live on the ROLE, not on the application, so they
# apply to every connection that role ever makes — including one opened by a
# future bug, a psql session, or anything else.
psql_su >/dev/null <<SQL
ALTER ROLE $LEARNER_ROLE SET default_transaction_read_only = on;
ALTER ROLE $LEARNER_ROLE SET statement_timeout = '5s';
ALTER ROLE $LEARNER_ROLE SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE $LEARNER_ROLE SET lock_timeout = '2s';
ALTER ROLE $LEARNER_ROLE SET search_path = learning;
SQL
ok "Applied role-level read-only, timeout and search_path settings to '$LEARNER_ROLE'"

# ---------------------------------------------------------------- databases --
ensure_db() {
  local db="$1"
  local exists; exists="$(echo "SELECT 1 FROM pg_database WHERE datname = '$db';" | psql_su)"
  if [[ "$exists" == "1" ]]; then
    warn "Database '$db' already exists (owner: $(echo "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$db';" | psql_su)) — left untouched."
  else
    echo "CREATE DATABASE $db OWNER $APP_ROLE ENCODING 'UTF8' TEMPLATE template0;" | psql_su >/dev/null
    ok "Created database '$db' owned by '$APP_ROLE'"
  fi

  # Harden. PUBLIC loses everything; the learner gets CONNECT and nothing more
  # at this level. Note the deliberate absence of TEMPORARY for the learner:
  # without it, CREATE TEMP TABLE fails even if some other check were bypassed.
  psql_su_db "$db" >/dev/null <<SQL
REVOKE ALL ON DATABASE $db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE $db TO $APP_ROLE;
GRANT CONNECT ON DATABASE $db TO $LEARNER_ROLE;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO $APP_ROLE;
REVOKE ALL ON SCHEMA public FROM $LEARNER_ROLE;
SQL
  ok "Hardened '$db' (PUBLIC revoked; learner has CONNECT only until migrations grant SELECT)"
}

ensure_db "$APP_DB"
ensure_db "$TEST_DB"

# ------------------------------------------------------------------- .env ----
umask 077
TMP_ENV="$(mktemp)"
cat > "$TMP_ENV" <<ENVEOF
# sql-interview-mastery local secrets — GENERATED BY scripts/provision-db.sh
# DO NOT COMMIT. This file is listed in .gitignore.
# Regenerate with: bash scripts/provision-db.sh

SQLMASTERY_DB_HOST=$HOST
SQLMASTERY_DB_PORT=$PORT
SQLMASTERY_DB_NAME=$APP_DB
SQLMASTERY_TEST_DB_NAME=$TEST_DB

SQLMASTERY_APP_USERNAME=$APP_ROLE
SQLMASTERY_APP_PASSWORD=$APP_PW

SQLMASTERY_LEARNER_USERNAME=$LEARNER_ROLE
SQLMASTERY_LEARNER_PASSWORD=$LEARNER_PW

SQLMASTERY_PLAYGROUND_STATEMENT_TIMEOUT_MS=3000
SQLMASTERY_PLAYGROUND_MAX_ROWS=500
SQLMASTERY_PLAYGROUND_MAX_SQL_LENGTH=10000

SQLMASTERY_SERVER_PORT=8085
VITE_API_BASE_URL=
ENVEOF

# Preserve any extra keys the user added by hand.
if [[ -f "$ENV_FILE" ]]; then
  while IFS= read -r line; do
    [[ "$line" == \#* || -z "$line" ]] && continue
    grep -q "^${line%%=*}=" "$TMP_ENV" || printf '%s\n' "$line" >> "$TMP_ENV"
  done < "$ENV_FILE"
fi
mv "$TMP_ENV" "$ENV_FILE"
chmod 600 "$ENV_FILE"
ok "Wrote $ENV_FILE (mode 0600)"

# ------------------------------------------------------------- verification --
verify_login() {
  local role="$1" pw="$2" db="$3"
  if PGPASSWORD="$pw" psql -h "$HOST" -p "$PORT" -U "$role" -d "$db" -qtAX \
       -c 'SELECT 1;' >/dev/null 2>&1; then
    ok "Verified: '$role' can log in to '$db' over TCP with password auth"
  else
    warn "Could not verify '$role' login over TCP at $HOST:$PORT."
    warn "Check that pg_hba.conf has a scram-sha-256 entry for host $HOST/32."
  fi
}
verify_login "$APP_ROLE" "$APP_PW" "$APP_DB"
verify_login "$LEARNER_ROLE" "$LEARNER_PW" "$APP_DB"

for r in "$APP_ROLE" "$LEARNER_ROLE"; do
  s="$(echo "SELECT rolsuper FROM pg_roles WHERE rolname='$r';" | psql_su)"
  [[ "$s" == "f" ]] && ok "Confirmed '$r' is NOT a superuser" || die "'$r' reports rolsuper=$s"
done

ro="$(PGPASSWORD="$LEARNER_PW" psql -h "$HOST" -p "$PORT" -U "$LEARNER_ROLE" -d "$APP_DB" -qtAX \
        -c 'SHOW default_transaction_read_only;' 2>/dev/null || echo '?')"
[[ "$ro" == "on" ]] && ok "Confirmed '$LEARNER_ROLE' sessions default to READ ONLY" \
                    || warn "default_transaction_read_only reported '$ro' for '$LEARNER_ROLE'"

echo
echo "  Done. Next:"
echo "    cd backend && ./mvnw spring-boot:run     # applies Flyway migrations and seeds the data"
echo "    cd frontend && npm install && npm run dev"
echo

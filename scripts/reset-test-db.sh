#!/usr/bin/env bash
#
# sql-interview-mastery — wipe the integration-test database
# ----------------------------------------------------------
# Drops every schema the application owns in sqlmastery_test_db, along with
# Flyway's history table, so the next test run applies the migrations to an
# empty database.
#
# This is safe to run at any time and destroys nothing of value: the test
# database holds no durable state. Everything in it is either created by a
# migration or loaded from the committed seed file.
#
#   IT IS NOT SAFE TO POINT AT THE APPLICATION DATABASE. The script refuses
#   to run against any database whose name is not the configured
#   SQLMASTERY_TEST_DB_NAME, because "drop every schema" is a verb that
#   should only ever have one target.
#
# When you need it:
#
#   * after changing a migration that has already been applied, which Flyway
#     refuses to re-run because its checksum moved;
#   * after an interrupted test run left the schemas created and the history
#     table not updated;
#   * whenever you want the migrations themselves tested from scratch, which
#     is the main reason the integration tests do not clean up after
#     themselves.
#
# Usage:
#   bash scripts/reset-test-db.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"

ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die() { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || die ".env not found. Run: bash scripts/provision-db.sh"

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

TEST_DB="${SQLMASTERY_TEST_DB_NAME:-}"
APP_DB="${SQLMASTERY_DB_NAME:-}"

[[ -n "$TEST_DB" ]] || die "SQLMASTERY_TEST_DB_NAME is not set in .env"
[[ "$TEST_DB" != "$APP_DB" ]] || die \
  "SQLMASTERY_TEST_DB_NAME and SQLMASTERY_DB_NAME are the same database ($TEST_DB).
     Refusing to drop schemas — fix .env first."

echo
echo "sql-interview-mastery :: resetting $TEST_DB"
echo "----------------------------------------------"

PGPASSWORD="$SQLMASTERY_APP_PASSWORD" psql \
  -h "${SQLMASTERY_DB_HOST:-127.0.0.1}" \
  -p "${SQLMASTERY_DB_PORT:-5433}" \
  -U "$SQLMASTERY_APP_USERNAME" \
  -d "$TEST_DB" \
  -v ON_ERROR_STOP=1 -qtAX <<'SQL'
-- One transaction: either the database is fully reset or it is untouched.
begin;
drop schema if exists app cascade;
drop schema if exists learning cascade;
drop schema if exists learning_alt cascade;
drop table if exists public.flyway_schema_history;
commit;
SQL

ok "Dropped app, learning and learning_alt, and Flyway's history"
echo
echo "  The next test run will apply every migration from scratch, which is"
echo "  the point — it means \"do the migrations work on an empty database\""
echo "  is checked rather than assumed."
echo
echo "  Run:  cd backend && ./mvnw verify -Dgroups=integration"
echo

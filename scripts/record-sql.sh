#!/usr/bin/env bash
#
# sql-interview-mastery — record the output of every statement in the curriculum
# -----------------------------------------------------------------------------
# Runs the backend under the "record" profile. That profile replaces the normal
# startup with CurriculumSqlRecorder, which:
#
#   1. parses and validates every lesson and problem,
#   2. executes every `sql` and `solutionSql` it finds, through the SAME
#      QueryExecutionService and the SAME least-privileged role the playground
#      uses, and
#   3. writes what PostgreSQL actually returned to
#      backend/src/main/resources/curriculum/expected-output.json.
#
# Nothing in that file is ever written by hand. A tutorial whose example shows a
# result its own query does not produce is the defect this exists to make
# impossible, and it is invisible to review because a plausible wrong result
# reads exactly like a right one.
#
# Run this after editing any SQL in the curriculum. The normal startup refuses
# to boot when a statement has no recorded output, so forgetting is loud.
#
# Usage:
#   bash scripts/record-sql.sh
#
# Requires: a running database (bash scripts/db.sh start) provisioned by
# scripts/provision-db.sh, so that .env exists.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_DIR/backend"
OUTPUT="$BACKEND_DIR/src/main/resources/curriculum/expected-output.json"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

echo
echo "sql-interview-mastery :: recording curriculum output"
echo "-----------------------------------------------------"

[[ -f "$PROJECT_DIR/.env" ]] || die ".env not found. Run: bash scripts/provision-db.sh"

cd "$BACKEND_DIR"

# -Dspring-boot.run.profiles rather than SPRING_PROFILES_ACTIVE so that the
# setting cannot leak into a shell the developer keeps using afterwards.
#
# The recorder writes relative to the working directory, which is why this runs
# from backend/ — the default value of sqlmastery.curriculum.record-to is
# src/main/resources/curriculum/expected-output.json.
./mvnw -q -B spring-boot:run \
  -Dspring-boot.run.profiles=record \
  -Dspring-boot.run.jvmArguments="-Dlogging.pattern.console=%msg%n"

[[ -f "$OUTPUT" ]] || die "The recorder finished but wrote no file. That should not happen."

# jq is not assumed: the count is read with a tool every machine has.
ENTRIES="$(grep -c '^  "' "$OUTPUT" || true)"
ok "Recorded $ENTRIES distinct statements"
ok "Wrote ${OUTPUT#"$PROJECT_DIR"/}"
echo
echo "  Review the diff before committing. A changed result is either a content"
echo "  change you made or a dataset change you did not expect, and the second"
echo "  one is worth stopping for."
echo

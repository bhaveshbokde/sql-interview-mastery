#!/usr/bin/env bash
#
# Runs once, the first time the Docker PostgreSQL container starts, from
# /docker-entrypoint-initdb.d.
#
# WHY THIS WRAPPER EXISTS
# -----------------------
# The entrypoint runs a bare .sql file through psql with no variables, so a
# .sql file can only contain literal passwords. It did, and they were fixed
# strings that duplicated the defaults in docker-compose.yml. That is worse
# than it sounds: the two copies could drift. Setting SQLMASTERY_APP_PASSWORD
# in .env changed the password the backend connected with but not the one the
# role was created with, and the stack came up with an authentication failure
# that pointed nowhere near the cause.
#
# So the SQL is parameterised and this script passes the real values in. The
# .sql is mounted outside /docker-entrypoint-initdb.d precisely so the
# entrypoint does not also run it bare, without the variables.
set -euo pipefail

require() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "FATAL: $name is not set." >&2
    echo "       docker-compose.yml requires it; copy .env.example to .env and" >&2
    echo "       set it, or run: openssl rand -base64 24 | tr -d /+= | cut -c1-32" >&2
    exit 1
  fi
}

require SQLMASTERY_APP_PASSWORD
require SQLMASTERY_LEARNER_PASSWORD

# The two roles must not share a password. The learner's whole purpose is to
# be the weaker identity; if the strings match, a leaked learner password is
# also the application password.
if [[ "$SQLMASTERY_APP_PASSWORD" == "$SQLMASTERY_LEARNER_PASSWORD" ]]; then
  echo "FATAL: the app and learner passwords are identical." >&2
  echo "       The learner role is the containment boundary for playground SQL;" >&2
  echo "       sharing its password with the owning role removes the boundary." >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v app_password="$SQLMASTERY_APP_PASSWORD" \
  -v learner_password="$SQLMASTERY_LEARNER_PASSWORD" \
  -f /opt/sqlmastery/initdb.sql

echo "sqlmastery: roles and databases created."

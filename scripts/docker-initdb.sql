-- Runs once, the first time the Docker PostgreSQL container starts.
--
-- Creates the same two roles and two databases that scripts/provision-db.sh
-- creates for a user-space cluster, so the two startup paths produce the same
-- database and the application cannot tell them apart.
--
-- The passwords are psql variables, supplied by scripts/docker-initdb.sh from
-- the Compose environment. They are NOT literals here, and that is the point:
-- when they were literals they silently disagreed with the values the backend
-- was configured with. psql's :'name' form quotes and escapes the value, so a
-- password containing a quote is handled correctly rather than ending the
-- statement.
--
-- This file is mounted OUTSIDE /docker-entrypoint-initdb.d. The entrypoint
-- runs any .sql it finds there through psql with no variables, which would
-- fail here -- and should, rather than creating roles with broken passwords.
--
-- Note what is NOT here: the grants on the learning schemas. Those belong to
-- the Flyway migration, because they have to be reapplied whenever a
-- migration adds a table, and a one-time init script cannot do that.

-- ---------------------------------------------------------------- roles --
create role sqlmastery_app
    with login nosuperuser nocreatedb nocreaterole noreplication inherit
    password :'app_password';

create role sqlmastery_learner
    with login nosuperuser nocreatedb nocreaterole noreplication inherit
    password :'learner_password';

-- The learner's safety rails live on the role, not on the application, so
-- they apply to every connection that role ever makes — including one opened
-- by a future bug or by a psql session.
alter role sqlmastery_learner set default_transaction_read_only = on;
alter role sqlmastery_learner set statement_timeout = '5s';
alter role sqlmastery_learner set idle_in_transaction_session_timeout = '10s';
alter role sqlmastery_learner set lock_timeout = '2s';
alter role sqlmastery_learner set search_path = learning;

-- ------------------------------------------------------------ databases --
-- POSTGRES_DB already created sqlmastery_db; hand it to the app role.
alter database sqlmastery_db owner to sqlmastery_app;

create database sqlmastery_test_db owner sqlmastery_app encoding 'UTF8' template template0;

-- --------------------------------------------------------------- grants --
revoke all on database sqlmastery_db from public;
grant connect, temporary on database sqlmastery_db to sqlmastery_app;
-- CONNECT and nothing else. Note the deliberate absence of TEMPORARY: without
-- it, CREATE TEMP TABLE fails for the learner even if some other check were
-- bypassed.
grant connect on database sqlmastery_db to sqlmastery_learner;

revoke all on database sqlmastery_test_db from public;
grant connect, temporary on database sqlmastery_test_db to sqlmastery_app;
grant connect on database sqlmastery_test_db to sqlmastery_learner;

\connect sqlmastery_db
revoke create on schema public from public;
alter schema public owner to sqlmastery_app;
revoke all on schema public from sqlmastery_learner;

\connect sqlmastery_test_db
revoke create on schema public from public;
alter schema public owner to sqlmastery_app;
revoke all on schema public from sqlmastery_learner;

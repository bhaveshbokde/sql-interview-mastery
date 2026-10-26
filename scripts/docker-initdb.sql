-- Runs once, the first time the Docker PostgreSQL container starts.
--
-- Creates the same two roles and two databases that scripts/provision-db.sh
-- creates for a user-space cluster, so the two startup paths produce the same
-- database and the application cannot tell them apart.
--
-- The passwords here come from the Compose environment and default to fixed
-- development values. That is acceptable for a container bound to localhost
-- and is the reason the file says so out loud: these are not secrets, they
-- are placeholders, and a deployment that reached further than a laptop would
-- have to replace them.
--
-- Note what is NOT here: the grants on the learning schemas. Those belong to
-- the Flyway migration, because they have to be reapplied whenever a
-- migration adds a table, and a one-time init script cannot do that.

-- ---------------------------------------------------------------- roles --
create role sqlmastery_app
    with login nosuperuser nocreatedb nocreaterole noreplication inherit
    password 'compose-app-password';

create role sqlmastery_learner
    with login nosuperuser nocreatedb nocreaterole noreplication inherit
    password 'compose-learner-password';

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

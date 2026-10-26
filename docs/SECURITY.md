# Security

This application accepts SQL from a stranger and executes it. That is its
purpose, so the question is not whether to allow it but what contains it.

## The threat model, stated plainly

**What is being protected:**

- the `app` schema, which holds the problem solutions — a learner reading them
  spoils the exercise;
- the learning dataset, which is shared, so one person changing it breaks the
  recorded output every lesson displays;
- the server's availability, against a query that never finishes;
- the host, against anything that reaches outside the database.

**What is not:**

- the learning data's confidentiality. It is a fictional dataset the
  application already displays in full.
- the API itself. There is no authentication and the `X-Learner-Id` header is
  not a credential — anyone who can reach the API can read or reset any
  learner's progress. That is acceptable for a tool bound to localhost and
  would be the first thing to change if it were not.

## Four layers

In order of how much they are relied upon. The ordering is deliberate and it
is the opposite of the intuitive one: writing a validator first and granting
privileges second produces a system where a parser bug is a breach.

### 1. Privileges — the actual boundary

The playground runs under `sqlmastery_learner`, whose entire grant list in the
cluster is:

- `CONNECT` on the database
- `USAGE` on `learning` and `learning_alt`
- `SELECT` on their tables, plus a default privilege so a table added by a
  later migration is readable without anyone remembering to grant it

It is `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`, it has **no
privilege of any kind** on the `app` schema, and it is not granted `TEMPORARY`
on the database — so `CREATE TEMP TABLE` fails even if every other check were
bypassed.

```
$ psql -U sqlmastery_learner -c 'select count(*) from app.problem_solution'
ERROR:  permission denied for schema app
```

This is PostgreSQL refusing, not the application. It is the only layer that
would still hold if every line of Java were wrong.

### 2. Read-only transactions — on the role, not the application

```sql
ALTER ROLE sqlmastery_learner SET default_transaction_read_only = on;
ALTER ROLE sqlmastery_learner SET statement_timeout = '5s';
ALTER ROLE sqlmastery_learner SET idle_in_transaction_session_timeout = '10s';
ALTER ROLE sqlmastery_learner SET lock_timeout = '2s';
ALTER ROLE sqlmastery_learner SET search_path = learning;
```

Set on the **role**, so they apply to every connection it ever makes —
including one opened by a future bug, by a migration script, or by someone at
a psql prompt. The application cannot forget to request them because it does
not request them.

```
$ psql -U sqlmastery_learner -c "insert into learning.departments values (99,'x',null,null)"
ERROR:  cannot execute INSERT in a read-only transaction
```

The connection pool repeats them in its init SQL, and every statement runs
inside an explicit transaction that is **always rolled back**, successful or
not. A `SELECT` has nothing to commit, so the rollback costs nothing; what it
buys is that no path through the code can leave work behind, and the
connection returns to the pool with no `SET LOCAL` the next borrower could
inherit.

### 3. The extended query protocol

Statements are sent through `PreparedStatement`. PostgreSQL does not accept
multiple statements in one extended-protocol message, so `select 1; drop table
t` is refused by the server regardless of what the validator noticed.

This is also why `JdbcPlaceholders` exists: JDBC reserves `?` as the bind
placeholder and PostgreSQL uses it as the jsonb key-existence operator, so a
valid query reaches the driver as one with an unbound parameter. The escape is
applied after validation and before execution, and it has to skip question
marks inside string literals, quoted identifiers, dollar-quoted blocks and
comments — which is why it is a scanner rather than a `replace` call.

### 4. The validator — the weakest layer, and the one you see

`SqlSafetyValidator` parses the statement with JSqlParser and walks the AST.
It refuses:

| | |
|---|---|
| anything that is not a single `SELECT` | including `WITH` that modifies data |
| more than one statement | |
| a data-modifying CTE | `WITH x AS (DELETE ... RETURNING *) SELECT * FROM x` |
| `SELECT ... INTO` | it creates a table |
| `FOR UPDATE` / `FOR SHARE` | row locks would block other learners |
| a schema outside the allow-list | `app`, `pg_catalog`, anything else |
| a name with more than two parts | could reach another database |
| a denied function | `pg_sleep`, `pg_read_file`, `dblink`, `lo_import`, … |
| a statement over the length limit | checked **before** parsing |

The data-modifying CTE is the case that justifies parsing rather than
pattern-matching. Its first keyword is `WITH` and it deletes the table; every
check that looks at the leading word waves it through. There is a test for it.

**This layer is not trusted.** It is a Java reimplementation of SQL grammar
reading a statement that PostgreSQL will parse with a different parser, and
anywhere the two disagree, this one is wrong. Its job is to turn a refusal
into a teachable message — "the playground is read-only, here is why" — and to
shrink the surface the other three have to defend. A bug in it is a bad error
message, not a breach.

## Resource limits

| Limit | Enforced by | Why there |
|---|---|---|
| `statement_timeout` 3 s | PostgreSQL, per transaction | the database cancels the query rather than Java abandoning a thread |
| `setMaxRows` 500 | the JDBC driver | stops the driver materialising more than that, so a large join cannot exhaust the heap |
| `lock_timeout` 2 s | PostgreSQL | a query that waits for a lock fails rather than hanging |
| `idle_in_transaction_session_timeout` 10 s | PostgreSQL | a forgotten open transaction cannot block vacuum indefinitely |
| max 8 concurrent | a process-wide semaphore | a burst is refused with 503 rather than queued into connection starvation |
| 120 queries/minute/client | an in-memory token bucket | stops one buggy tab exhausting the pool |
| 10 000 characters | the validator, before parsing | the parser is itself attack surface |
| 64 KB request body | Tomcat | caps the input before any application code sees it |

The rate limiter keys on a client-supplied header falling back to the remote
address. Both are trivially spoofable, which is fine: it exists to stop one
buggy tab exhausting the connection pool, not to stop an adversary reading
sample data they are already being shown.

## Injection

The application's own queries are all parameterised prepared statements. The
protection is structural rather than a matter of escaping — the statement is
parsed and planned before any value is bound, so a value cannot become part of
the structure.

Parameters cannot substitute **identifiers**, and there are exactly two places
where a caller-supplied string reaches SQL text:

1. `SET LOCAL search_path = <schema>` in the query executor
2. the schema name in the dataset introspection endpoint

Both resolve the name against the configured allow-list **by equality** and
use the matched entry from the list — never the caller's string. An
unrecognised name produces an error rather than an escaped version of itself.
`DatasetController.requireAllowedSchema` and
`QueryExecutionService.resolveSchema` are the two functions, and they are
short on purpose.

## What leaves the server

`GlobalExceptionHandler` returns a deliberate exception's message verbatim and
replaces everything else. A `SqlRejectedException` carries text written to be
read by a learner; an unexpected exception might carry a connection string, a
file path or a fragment of an internal query, so its message is replaced and
the detail goes to the log.

`server.error.include-message: never` and `include-stacktrace: never` close the
same hole for errors Spring handles before the advice does.

Learner SQL is logged at DEBUG only. It is not secret, but logging every
keystroke-triggered query at INFO makes the log useless for anything else.

## Secrets

Passwords are generated from `/dev/urandom` by `scripts/provision-db.sh`,
written only to `.env` with mode 0600, and passed to `psql` on stdin — never
as a command-line argument, so they do not appear in `ps` output or in shell
history. `.env` is gitignored; `.env.example` holds placeholders and is
committed.

The Docker Compose defaults are fixed development passwords and the file says
so. They are not secrets; they are placeholders for a container bound to
localhost.

## If this were exposed beyond a laptop

In order:

1. **Authentication**, and make `learner_id` come from the session rather than
   from a header.
2. **A per-user rate limit** that is not spoofable, and a global query budget.
3. **A connection pool per tenant**, or `SET ROLE` with careful reset, so one
   learner's runaway query cannot starve another's.
4. **`pg_stat_statements` monitoring** on the learner role, to see what is
   actually being run.
5. Reconsider `information_schema` access. It is allowed here because the
   lessons teach it and PostgreSQL filters those views by privilege — the
   learner role sees only objects it already has access to. That reasoning
   holds, and it is worth re-examining rather than inheriting.

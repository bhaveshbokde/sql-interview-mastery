# Architecture

## The shape

```
Browser  ──►  Vite dev server (:5173)  ──proxy──►  Spring Boot (:8085)  ──►  PostgreSQL (:5433)
                                                         │                        │
                                                         │                   app schema         content + progress
                                                         │                   learning           the dataset
                                                         │                   learning_alt       the dataset, hostile
                                                         │
                                                   two connection pools
                                                   app      — owns everything, can write
                                                   learner  — SELECT on two schemas, nothing else
```

## Two connection pools, and why not one

The whole security model rests on the playground using a different identity
from the rest of the application, so the two are separate `DataSource` beans
with separate Hikari pools.

Not one pool with `SET ROLE`: that can be reset by the session holding it, and
a connection returned to a shared pool in the wrong state is a class of bug
this design does not have. The learner pool is also deliberately small —
beyond a burst, requests are refused quickly rather than allowed to open
connections until `max_connections` is the thing that fails — and it carries
its limits in `connectionInitSql`, so they apply to every connection rather
than to every call site that remembered.

Spring Boot's `DataSourceAutoConfiguration` is switched off because of this.

## Content is files; the database is a projection

```
resources/curriculum/**.yaml          the source of truth
          ↓  CurriculumLoader, on every startup, in one transaction
    app.module / app.lesson / app.problem / app.problem_hint
    app.problem_solution / app.quiz_question
```

Every startup deletes the content tables and reloads them. That rules out the
failure mode where the database holds content nobody can find the source of,
and makes the authoring workflow "edit a file, restart" with no migration
step in between.

Three things happen between reading and writing:

1. **`CurriculumValidator`** refuses incomplete content, collecting every
   complaint before throwing any. Fixing content one restart per error is
   miserable.
2. **`ResultInjector`** attaches each statement's recorded output, and
   refuses content whose statements have never been executed.
3. **Hints and solutions are split out** of the problem body into their own
   tables, so the endpoint serving a problem has nothing to leak. "The answer
   is in a different table that this query does not join to" stays true on its
   own, where "do not include the answer in the response" is a rule somebody
   has to keep remembering.

The progress tables are the exception: they are the only durable state and the
loader never touches them. They reference content by plain text with **no
foreign key**, deliberately — a content reload that dropped a lesson would
otherwise delete the learner's history of having studied it.

## How expected output gets there

```
scripts/record-sql.sh
   → Spring Boot under the "record" profile
   → CurriculumSqlRecorder
       reads every lesson and problem
       finds every statement via CurriculumSql
       executes each through QueryExecutionService  ← the same service the editor uses
       writes curriculum/expected-output.json, keyed by sha256(schema + NUL + normalised sql)
```

Two properties matter more than the mechanism:

**It runs through the real execution path.** Same least-privileged role, same
read-only transaction, same validator. So the recording is also a check that
every example in the course is a query a learner could actually run — a lesson
cannot teach something the playground forbids without the recording step
failing.

**The key is a digest of the statement, not its position.** An output can only
attach to the exact statement that produced it, so editing a query without
re-recording removes its output rather than silently inheriting the old one.
The loader treats a missing output as a startup failure.

`CurriculumSql` is the single definition of "what counts as a statement", used
by the recorder, the injector and the tests. If any two disagreed, the symptom
would be a statement that is displayed but never checked — exactly the defect
the arrangement exists to prevent.

## Request paths

**Running a query**

```
POST /api/playground/query
  → QueryRateLimiter          token bucket per client
  → SqlSafetyValidator        parse, walk the AST, refuse or return the statement
  → QueryExecutionService     semaphore · learner pool · explicit read-only transaction
      SET LOCAL statement_timeout / search_path
      JdbcPlaceholders.escape  ?  →  ??    (JDBC vs jsonb)
      PreparedStatement, setMaxRows(limit + 1)
      read rows, convert values
      ALWAYS roll back
  → QueryResult, or SqlErrorMapper turning a PSQLException into a usable message
```

Reading one row more than the limit is what lets "there were more" be
distinguished from "there were exactly this many". Truncating silently is how
someone concludes their `GROUP BY` is wrong.

**Checking an answer**

```
POST /api/problems/{id}/evaluate
  → run the submission on learning       ─┐
  → run the submission on learning_alt    ├─ all four through the same validator and pool
  → compare each against the reference   ─┘
  → ResultComparator: columns first, then rows as multisets unless order matters
  → verdict: CORRECT | CORRECT_ON_SAMPLE_ONLY | INCORRECT | DID_NOT_RUN | REFUSED
  → ProgressService records the attempt, server-side
```

The attempt is recorded by the endpoint that judged it, not by the client. A
history assembled from what a browser chose to report is a history of claims.

## Package layout

| Package | |
|---|---|
| `playground` | the editor's endpoint, the validator, execution, error mapping, rate limiting |
| `curriculum` | loading, validating and serving lessons, problems and quiz questions |
| `evaluation` | running a submission against both datasets and comparing |
| `progress` | lesson status, attempt history, the SM-2 scheduler |
| `dataset` | schema introspection and the reset verb |
| `config` | the two DataSources, CORS, playground properties, `.env` loading |
| `error` | one exception handler, deliberately asymmetric |

The asymmetry in `GlobalExceptionHandler` is the point of that package: a
`SqlRejectedException` carries a message written for a learner and is returned
verbatim; anything else might carry a connection string or an internal query
fragment, so its message is replaced and the detail goes to the log.

## Frontend

React 18, TypeScript, Vite. No state library and no query library — this is
ten screens of text and tables against a local server, and what was actually
needed is in `useApi`: discard a response whose request has been superseded,
and do not set state after unmount. Both are the real bugs this pattern has.

CodeMirror with the PostgreSQL dialect provides the editor, with completion
built from the live schema so the dataset is explorable rather than guessed
at. It is code-split, because it is most of the bundle and only two pages need
it.

The dev server proxies `/api` rather than relying on CORS, so the browser sees
one origin and nothing about development depends on the CORS configuration
being right. The production container does the same thing with nginx.

## Database schema

| Schema | |
|---|---|
| `app` | content projection and progress. The learner role has no privilege on it at all. |
| `learning` | the dataset the lessons use. 17 tables, deliberate NULLs, ties, gaps and an empty-to-be-joined-from table. |
| `learning_alt` | the same tables, rows chosen to break naive answers. Empty `order_items`, tied salaries, a department with nobody in it. |

Migrations:

| | |
|---|---|
| `V1__app_schema.sql` | content tables; searchable columns are real columns, bodies are jsonb |
| `V2__learning_schema.sql` | both learning schemas, indexes on one only so `EXPLAIN` has something to compare, grants |
| `V3__learning_views.sql` | two views, so the views lesson has something real to query |
| `V4__progress.sql` | progress and revision, soft-referencing content on purpose |

Lesson and problem bodies are `jsonb` rather than a dozen tables. A lesson has
fourteen required parts of wildly different shapes, no query ever filters on
any of them, and modelling each as its own table would mean a dozen joins to
render one page. The columns people search, sort and filter on — title, slug,
difficulty, topic, tags — are real columns with real indexes.

Full-text search is a `tsvector` generated column with a GIN index, weighted
so a title match outranks a body match. The same machinery the full-text
lesson describes, which is the general pattern here: where the application
needs something a lesson teaches, it uses the thing the lesson teaches.

## Decisions and their costs

| Decision | Cost accepted |
|---|---|
| No ORM | More code in the repositories. Worth it because the subject is SQL and an ORM would hide it. |
| Content in YAML, not the database | No editing UI. The workflow is a text editor and a restart. |
| Two datasets | The dataset generator and every recorded output exist twice. Worth it for `CORRECT_ON_SAMPLE_ONLY`. |
| Recorded output, never hand-written | A step people can forget — so the application refuses to start when they do. |
| No authentication | The API is wide open. Acceptable bound to localhost, and the first thing to change otherwise. |
| jsonb bodies | No referential integrity inside a lesson. The loader's validation is what replaces it. |
| A hand-rolled fetch hook | No background refetching or shared cache. Neither is needed at this size. |

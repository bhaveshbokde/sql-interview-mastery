# Testing

258 tests: 232 on the backend, 26 on the frontend.

## Running them

```bash
# Backend — everything. Needs a provisioned database.
cd backend && ./mvnw test

# Backend — unit tests only. Needs nothing.
cd backend && ./mvnw test -DexcludedGroups=integration

# Backend — integration tests only.
cd backend && ./mvnw test -Dgroups=integration

# Full verify: checkstyle, tests, JaCoCo report
cd backend && ./mvnw verify

# Frontend
cd frontend && npm test
```

If the integration tests fail at startup with *relation "departments" already
exists*, the test database is half-migrated from an interrupted run:

```bash
bash scripts/reset-test-db.sh
```

## Why there is no in-memory database

The subject of this application is PostgreSQL. jsonb containment, window
frames, `DISTINCT ON`, GIN indexes, read-only transactions, SQLSTATE codes,
`generate_series`, ordered-set aggregates — a test against H2 would pass while
proving nothing about any of it, and most of the curriculum's own SQL does not
parse on another engine.

So the integration tests run against `sqlmastery_test_db`, a real PostgreSQL
database created alongside the application's by `scripts/provision-db.sh`.
Flyway migrates it on the first test, which makes *do the migrations apply to
an empty database* something checked on every run rather than assumed.

The cost is that those tests need a database, so they are tagged
`integration` and can be excluded.

Testcontainers is wired into the build for CI, where a Docker daemon is
available. It is excluded by default because an unreachable daemon socket is
enough to stop a test run on a developer machine, and the same integration
tests already cover the database against a local PostgreSQL.

## What the backend tests cover

### Unit — no database

**`SqlSafetyValidatorTest`** (60 tests). The playground's refusals, one per
case: writes, DDL, multiple statements, data-modifying CTEs, `SELECT INTO`,
locking clauses, forbidden schemas including inside subqueries and CTEs,
three-part names, denied functions including nested in an expression, blank
and oversized input. Assertions are on the `Reason` enum, not on the message —
the reason is the contract the API exposes; the message is prose and should be
free to improve.

It also asserts what is **accepted**: CTEs, recursive CTEs, set operations,
window functions, `FILTER`, `VALUES`, `LATERAL`, `FETCH ... WITH TIES`. A
validator that refuses valid SQL is as broken as one that accepts invalid SQL,
and only the second kind of bug gets reported.

**`JdbcPlaceholdersTest`** (18). That `?` outside a literal is escaped and `?`
inside one is not — in string literals with doubled quotes, quoted
identifiers, dollar-quoted blocks, line comments and nested block comments.
The second property is what turns a correctness fix into a correctness bug if
it is written as a blanket `replace`.

**`RevisionSchedulerTest`** (26). The SM-2 ladder, the ease adjustment curve,
the floor, the cap, lapse handling, and the property most simplified
implementations lose: a lapse resets the interval and **keeps** the ease
penalty, so an item that keeps failing both returns immediately and grows more
slowly afterwards. The scheduler is pure, which is why it is a separate class
from the repository — this is the one piece that can be tested exhaustively
without a database.

**`ResultComparatorTest`** (17). Where the line sits between strict enough to
catch a wrong query and lenient enough not to fail a right one: `150` equals
`150.00`, NULL equals only NULL, a boolean is not the string `"true"`,
duplicates are counted rather than deduplicated, row order is ignored unless
the problem says otherwise, and columns are checked before rows. One test pins
a deliberate leniency — text that is unambiguously a number is accepted as
that number, so `to_char(salary, 'FM999999')` is not marked wrong — which is
the most arguable decision in the file and is recorded so that changing it is
a decision rather than an accident.

**`CurriculumValidatorTest`** (18) and **`CurriculumSqlTest`** (15). That
incomplete content fails the startup, one missing field at a time; that
dangling cross-references are caught; that a quiz `answerIndex` out of range
is caught. And the statement-discovery rules the recorder, the injector and
the loader all depend on agreeing about — including that a `lineByLine`
fragment is **not** treated as an executable statement, which is why that
field is called `clause`.

### Integration — real PostgreSQL, real HTTP

**`PlaygroundApiTest`** (27). A valid query returns typed columns and reports
its limits; NULL arrives as JSON null and is distinguishable from an empty
string; `learning_alt` is reachable; the jsonb `?` operator works; truncation
is reported rather than hidden. PostgreSQL's own errors come back as their
SQLSTATE with the position and an explanation. Every refusal case is
exercised over HTTP — and then the dataset row count is asserted unchanged,
which is the assertion that would catch any of them having leaked through.

**`CurriculumApiTest`** (24). Every lesson is reachable by id and by slug;
every lesson has all fourteen parts populated after the round trip through
jsonb; **every example carries recorded output**; every problem endpoint
returns no solution, no hints, no explanation and no `expected`; hints are
served one at a time; every extra test ran against the schema it declares.

And the one that matters most: **every reference solution is evaluated and
must come back CORRECT**. A reference solution that did not pass its own
checker would make the problem unanswerable, and running all seventeen is the
cheapest possible guard. The same suite checks that the classic wrong answer
to second-highest-salary is caught as `CORRECT_ON_SAMPLE_ONLY`.

**`ProgressApiTest`** (24). Lesson status and view counts, attempt history
kept rather than overwritten, `solved` staying true after a later wrong
attempt, the revision ladder over HTTP, learner isolation, and that reset is
scoped to one learner and leaves the curriculum alone.

## What the frontend tests cover

**`ResultTable.test.tsx`** (9). That a NULL is drawn differently from an empty
string — the assertion the three-valued-logic lesson depends on, since a grid
that renders both as blank contradicts the lesson it is illustrating. Also
numeric alignment, booleans, arrays in brace notation, the truncation warning,
and that "no rows" says it is a result rather than an error.

**`client.test.ts`** (10). That a structured `QueryError` survives with its
`code` intact, because the UI branches on it; that a network failure says "is
the backend running" rather than "Failed to fetch"; that path segments are
encoded.

**`pages.test.tsx`** (7). That a lesson renders all fourteen sections; that
an exercise answer stays hidden until asked for; that the problem page does
not fetch or display a solution nobody requested; that hints arrive one at a
time and asking for the first does not reveal the second; that the dashboard
sparkline draws fourteen bars including the empty days.

## What is not tested, and why

**The seed data generator.** `tools/generate_dataset.py` is deterministic and
its output is committed, so the seed file is reviewed rather than the script.
Regenerating it and finding a diff is the check.

**The lesson prose.** No test asserts that an explanation is correct — that is
what review is for. What *is* checked mechanically is that every statement in
the prose runs and that its displayed output is what it produced.

**The CodeMirror editor itself.** Keystroke-level behaviour of a third-party
editor is its maintainers' test suite, not this one's. What is tested is the
page around it.

**Browser rendering.** There is no Playwright or Cypress layer. The component
tests cover the logic and a human looks at the pages; an end-to-end browser
suite would be the next thing to add if this grew.

**Concurrency.** The transaction and MVCC behaviour the lessons describe is
not exercised by a test that opens two sessions. Doing it properly needs a
harness that coordinates them, and the lessons are honest that the
demonstration belongs in two psql windows.

## Coverage

JaCoCo runs on `verify` and writes `target/site/jacoco/index.html`.

There is no coverage threshold, deliberately. A check rule only encourages
tests that raise a number rather than tests that find defects, and the ones
above were written by asking what would actually break.

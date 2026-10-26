# SQL Interview Mastery

An interactive SQL learning and interview-preparation application. React and
TypeScript on the front, Spring Boot and PostgreSQL behind it, with a real SQL
editor that runs your queries against a real database.

It covers the relational model through to advanced PostgreSQL — window
functions, recursive CTEs, MVCC and isolation levels, execution plans, JSONB
indexing, and the questions interviewers actually ask about each.

---

## The one idea worth knowing before you read anything else

**No expected output in this application was typed by a human.**

Every SQL statement in every lesson, exercise, problem solution, alternative
approach and wrong-answer demonstration is executed against the real dataset,
and whatever PostgreSQL returned is what the page displays. The recording runs
through the same service, under the same least-privileged role, with the same
read-only transaction that serves the editor.

That is not a build-system detail. Showing a result table that the query does
not produce is the most common defect in written SQL tutorials, it is invisible
to review because a plausible wrong result reads exactly like a right one, and
it is corrosive — a reader who follows the example and gets something different
concludes they have misunderstood. Here it is structurally impossible: the
application **refuses to start** if any statement in the curriculum has no
recorded output.

---

## Quick start

Two supported ways to get a database. Neither is the "real" one.

### Option A — user-space PostgreSQL, no Docker, no root

```bash
bash scripts/db.sh init && bash scripts/db.sh start   # PostgreSQL 16 in ~/.local
bash scripts/provision-db.sh                          # roles, databases, .env
cd backend  && ./mvnw spring-boot:run                 # migrations, seed, API on :8085
cd frontend && npm install && npm run dev             # UI on :5173
```

Then open <http://localhost:5173>.

This path needs the PostgreSQL **server** package (`postgresql-16` on Debian
and Ubuntu), not just `psql`. It runs the whole cluster inside your home
directory on port 5433, so it cannot collide with a system PostgreSQL on 5432
and needs no administrative access.

### Option B — Docker

```bash
docker compose up -d db                               # just the database
cd backend  && ./mvnw spring-boot:run
cd frontend && npm install && npm run dev
```

or the whole stack in containers:

```bash
docker compose up --build                             # UI on :5173, API on :8085
```

### Stopping

```bash
bash scripts/db.sh stop      # or: docker compose down
```

---

## What is in it

| | |
|---|---|
| **9 modules, 36 lessons** | foundations, aggregation, joins, intermediate SQL, window functions, schema design, transactions, performance, advanced PostgreSQL |
| **17 interview problems** | each with hints, a verified solution, alternatives, the wrong answers that look right, and the follow-up questions |
| **43 quiz questions** | the theory that has an answer and no query to run |
| **454 runnable statements** | every one executed and recorded against the real dataset |
| **17 tables, two datasets** | `learning` for the lessons, `learning_alt` holding the ties, NULLs and empty tables that break naive answers |

Each lesson has fourteen required parts — objectives, concept, syntax, sample
tables, examples with recorded output and a walkthrough, edge cases, pitfalls,
performance notes, interview questions with model answers, exercises,
a revision checklist, related topics and dialect notes. A lesson missing any
of them is a failed startup with the lesson id and the missing field in the
message, not a page with a blank section.

---

## The SQL editor

Type a query, press Cmd/Ctrl-Enter, see the rows. It is a real editor —
CodeMirror with the PostgreSQL dialect, schema-aware completion over the
actual tables, and a caret placed on the character PostgreSQL complained
about.

It is also **read-only, by four independent mechanisms**. In order of how much
they are relied upon:

1. **Privileges.** The connection belongs to a role whose entire grant list is
   `USAGE` on two schemas and `SELECT` on their tables. It has no rights at all
   on the `app` schema, so `select * from app.problem_solution` fails in
   PostgreSQL rather than being caught by a filter someone could forget.
2. **Read-only transactions.** The role carries
   `default_transaction_read_only = on`, so a write is refused by the server
   even if the role somehow held the privilege.
3. **The extended query protocol.** Statements go through `PreparedStatement`,
   and PostgreSQL rejects multiple statements in one extended-protocol
   message. That is a server-side guarantee against `; DROP TABLE`.
4. **A parsing validator.** Which exists to give a clear, early, teachable
   error rather than a raw `SQLException` — and which is the weakest layer,
   because it is a Java reimplementation of SQL grammar reading a statement
   that PostgreSQL will parse with a different parser.

The ordering is deliberate and it is the opposite of the intuitive one.
Writing a validator first and granting privileges second produces a system
where a parser bug is a breach. Here, a parser bug is a bad error message.

Full detail in [docs/SECURITY.md](docs/SECURITY.md).

---

## Answer checking

Submit an answer to a problem and it is run against **two** datasets, and
compared with the reference solution's output on both.

The second dataset is the point. `learning_alt` has the same tables with rows
chosen to break answers that happen to fit the sample — ties at the top of a
salary ranking, an empty `order_items`, a department with no employees, two
contacts sharing an email. So the checker has a verdict that most do not:

> **CORRECT_ON_SAMPLE_ONLY** — right on the sample data, wrong on the alternate
> dataset.

That is the verdict a candidate most needs, because it is the one an
interviewer would give. `ORDER BY salary DESC LIMIT 1 OFFSET 1` is the classic
answer to second-highest-salary, it passes on data with no tie at the top, and
it is wrong.

Comparison is as multisets unless the problem says row order is part of the
answer, so a correct query is not failed for ordering rows differently when
nothing asked for an order. Every response states what the check does and does
not establish — deciding whether two arbitrary SQL queries are equivalent is
not something any tool can do, and this one does not claim to.

---

## Progress and revision

Lessons can be marked complete with a confidence rating, problem attempts are
recorded server-side by the endpoint that judged them, and quiz answers are
tracked per topic so the progress page can say what to study next.

Completing a lesson or solving a problem puts it into a spaced-repetition
queue on an SM-2 schedule. The interval goes 1 day, 6 days, then multiplied by
a per-item ease factor that moves with how well you recalled it — so something
you keep forgetting keeps coming back, and something you find easy drifts out
to a few months. A low confidence rating or a solve that needed three hints
lowers the item's starting ease, which is the one place those inputs have a
mechanical effect rather than being a note to self.

There is no authentication. Progress is keyed on an `X-Learner-Id` header
defaulting to `local`, which exists so one person can keep two separate runs
through the course — not to separate two people. It is not a credential and
protects nothing, which is fine for a tool bound to localhost and would not be
for anything else.

---

## Layout

```
backend/                 Spring Boot 3.5, Java 21, JDBC (no ORM — the subject is SQL)
  src/main/java/         playground · curriculum · evaluation · progress · dataset
  src/main/resources/
    db/migration/        Flyway: app schema, learning schemas, views, progress
    db/learning/seed.sql generated, deterministic, re-runnable
    curriculum/          the content, as YAML
      lessons/           36 lessons across 9 module directories
      problems/          17 interview problems
      quiz.yaml          43 multiple-choice questions
      expected-output.json   GENERATED — never edited by hand
  src/test/java/         232 tests, unit and integration

frontend/                React 18, TypeScript, Vite, CodeMirror
  src/api/               typed client and the two hooks the pages use
  src/components/        result grid, SQL editor, runnable snippet, shared bits
  src/pages/             dashboard, curriculum, lesson, playground, problems,
                         problem, quiz, revision, search, progress
  src/test/              26 tests

scripts/                 db.sh · provision-db.sh · record-sql.sh · reset-test-db.sh
tools/                   generate_dataset.py — regenerates the seed deterministically
docs/                    SECURITY · TESTING · CONTENT · ARCHITECTURE
```

---

## Everyday commands

```bash
# Database
bash scripts/db.sh start|stop|status|psql     # the user-space cluster
bash scripts/provision-db.sh                  # roles, databases, .env (idempotent)
bash scripts/reset-test-db.sh                 # wipe the integration-test database

# Backend
cd backend
./mvnw spring-boot:run                        # start it
./mvnw test                                   # everything, needs a database
./mvnw test -DexcludedGroups=integration      # unit tests only, needs nothing
./mvnw verify                                 # plus checkstyle and coverage

# Content
bash scripts/record-sql.sh                    # re-run and re-record every statement

# Frontend
cd frontend
npm run dev                                   # dev server on :5173
npm test                                      # vitest
npm run build                                 # typecheck and production build
```

After editing any SQL in the curriculum, run `scripts/record-sql.sh`. The
normal startup refuses to boot when a statement has no recorded output, so
forgetting is loud rather than silent.

---

## Configuration

Everything is environment variables, written into `.env` by
`scripts/provision-db.sh` and loaded at startup. `.env.example` has the full
list with placeholders; `.env` holds real generated passwords and is
gitignored.

| Variable | Default | |
|---|---|---|
| `SQLMASTERY_DB_HOST` / `_PORT` | `127.0.0.1` / `5433` | 5433 so it cannot collide with a system PostgreSQL |
| `SQLMASTERY_DB_NAME` | `sqlmastery_db` | |
| `SQLMASTERY_TEST_DB_NAME` | `sqlmastery_test_db` | disposable; the integration tests migrate it from empty |
| `SQLMASTERY_APP_*` | | owns both schemas, runs Flyway, serves the API |
| `SQLMASTERY_LEARNER_*` | | the only role that ever runs SQL from the editor |
| `SQLMASTERY_PLAYGROUND_STATEMENT_TIMEOUT_MS` | `3000` | enforced by PostgreSQL, not by a timer in Java |
| `SQLMASTERY_PLAYGROUND_MAX_ROWS` | `500` | applied with `setMaxRows`, so the driver stops materialising |
| `SQLMASTERY_PLAYGROUND_MAX_SQL_LENGTH` | `10000` | checked before parsing — the parser is itself attack surface |
| `SQLMASTERY_SERVER_PORT` | `8085` | |
| `SQLMASTERY_CORS_ALLOWED_ORIGINS` | the Vite dev server | irrelevant when the frontend is served from the same origin |

API documentation is at <http://localhost:8085/swagger-ui.html> when the
backend is running.

---

## Design decisions worth knowing

**No ORM.** `spring-boot-starter-jdbc`, not `-data-jpa`. Every query is
written by hand so the code reads the way the lessons teach. An ORM would hide
exactly the thing being taught, and the repositories double as the worked
example the SQL-injection lesson points at when it says "this is what
parameterised looks like".

**The content is files; the database is a projection.** Every startup clears
the content tables and reloads them from YAML. That rules out the failure mode
where the database holds content nobody can find the source of, and makes the
authoring workflow "edit a file, restart" with no migration step. Progress
tables are the exception — they are the only durable state and the loader
never touches them.

**Two datasets, not one.** `learning_alt` exists so that a solution can be
checked against data designed to break it. Without it, "correct" means "fits
the sample", and that is the difference between passing an interview and
failing one.

**The validator parses rather than pattern-matches.** `WITH x AS (DELETE ...
RETURNING *) SELECT * FROM x` is a genuinely write-capable statement whose
first keyword is `WITH`. A prefix check waves it through. That single case is
the clearest argument for the AST walk, and there is a test for it.

---

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the pieces fit, and why
- [docs/SECURITY.md](docs/SECURITY.md) — the four layers, and what each is worth
- [docs/TESTING.md](docs/TESTING.md) — what is tested, how to run it, what is not
- [docs/CONTENT.md](docs/CONTENT.md) — authoring a lesson or a problem

---

## Requirements

Java 21 · Maven (the wrapper is included) · Node 20+ · PostgreSQL 16 server,
or Docker.

## Licence

Written as a portfolio project. The curriculum text is original; problems with
a public origin cite it, and problems without one are left unattributed rather
than given an invented source.

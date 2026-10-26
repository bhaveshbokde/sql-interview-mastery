# Authoring content

Content lives in `backend/src/main/resources/curriculum/`. Edit a file,
restart the backend, refresh the page. There is no migration step and no
editing UI.

```
curriculum/
  modules.yaml                 the nine modules
  lessons/01-foundations/      lessons, one file each, numbered for ordering
  problems/                    interview problems, one file each
  quiz.yaml                    multiple-choice questions
  expected-output.json         GENERATED — never edit this
```

## The workflow

```bash
# 1. Write or edit the YAML.
# 2. Execute every statement and record what it returned.
bash scripts/record-sql.sh
# 3. Restart. The loader validates, injects the results, and reloads.
cd backend && ./mvnw spring-boot:run
```

Step 2 is not optional. The loader refuses to start when a statement has no
recorded output, naming each one — so forgetting is loud. Changing so much as
a column name in a query invalidates its recording, because the key is a
digest of the statement.

## Two rules that are enforced rather than requested

**Every lesson has all fourteen parts.** Not most lessons, not the ones
somebody got round to. A missing part is a failed startup with the lesson id
and the field name in the message. The rule exists because the alternative —
a convention honoured for the first dozen items and quietly abandoned — gives
you a course where some pages have interview questions and some do not, with
no way to tell which without opening all of them.

**No expected output is written by hand.** It is recorded by executing the
statement. A tutorial that shows a result its own query does not produce is
the most common defect in SQL writing, it is invisible to review, and a reader
who follows the example and gets something different concludes they have
misunderstood.

## A lesson

```yaml
id: f-nulls                    # unique, stable; prefix by module
moduleId: foundations          # must exist in modules.yaml
position: 40                   # order within the module
title: NULL and three-valued logic
slug: nulls                    # the URL
difficulty: Beginner           # Beginner | Intermediate | Advanced
estimatedMinutes: 25
summary: >-
  One or two sentences. Shown in the module list and in search results.
tags: [null, three-valued-logic]

prerequisites: [f-filtering]   # lesson ids; may be empty

objectives:                    # 1 — what the reader will be able to do
  - State the three truth values and what AND, OR and NOT do with UNKNOWN

concept: |                     # 2 — the explanation, Markdown, GFM tables
  ## NULL means "unknown"
  ...

syntax:                        # 3 — the forms, with the rules that govern them
  - form: "expr IS NULL"
    notes: The only correct way to test for NULL.

sampleTables: [employees]      # 4 — the dataset tables this lesson uses

examples:                      # 5, 6, 7 — runnable SQL, output, walkthrough
  - title: The three truth values, printed
    sql: |
      select null = null as null_equals_null
    schema: learning_alt       # optional; omit for `learning`
    walkthrough: |
      Six of the nine columns come back empty...

edgeCases:                     # 8 — boundaries, shown rather than described
  - title: NOT IN with a NULL returns nothing
    body: >-
      The first count is zero...
    sql: |
      select 1
    schema: learning_alt       # optional, same as examples

pitfalls:                      # 9
  - mistake: Treating NULL as zero
    why: Most clients render them alike.
    fix: Check with IS NULL.

performance: |                 # 10 — OPTIONAL. Omit rather than pad.
  IS NULL is sargable in PostgreSQL.

interviewQuestions:            # 11
  - question: What is the difference between NULL, 0 and an empty string?
    answer: >-
      ...

exercises:                     # 12
  - prompt: >-
      Return every employee with a department label.
    difficulty: Easy           # Easy | Medium | Hard
    hint: A LEFT JOIN keeps the employees with no department.
    solutionSql: |
      select 1

checklist:                     # 13 — the revision list
  - NULL means unknown

related:                       # 14 — lesson or problem ids, must resolve
  - f-filtering

dialect:
  standard: What the SQL standard says.
  postgres: What PostgreSQL adds or does differently.
  mysql: How MySQL differs, where an interviewer is likely to ask.
```

`performance` is the only optional part. Not every lesson has something true
to say about performance, and a paragraph written to fill a slot is worse than
an absent one.

### `schema` has to be a sibling of `sql`

A `schema:` key is only read when it sits on the **same object** as the
`sql:` it applies to. Putting it one level up is silently ignored, the
statement runs against `learning`, and the recorded output contradicts what
the prose claims. This has happened; the loader now also rejects any key that
matches no field, which is the other half of the fix.

## A problem

Same idea, different required parts. See
`problems/p01-second-highest-salary.yaml` as the worked example.

```yaml
id: p01-second-highest-salary
position: 10
title: Second-highest salary
slug: second-highest-salary
difficulty: Easy               # Easy | Medium | Hard
topic: Window functions
tags: [ranking, ties]

source:                        # OPTIONAL — but all-or-nothing
  name: LeetCode 176 — Second Highest Salary
  url: https://leetcode.com/problems/second-highest-salary/

orderSensitive: false          # true only when row order is part of the answer

statement: |                   # Markdown
  Return the second-highest **distinct** salary...

tables: [employees]            # shown beside the question with their schema
expectedColumns: [salary, full_name]   # checked before the rows are
edgeCases:                     # what to think about before writing anything
  - Several employees may share the second-highest salary.
hints:                         # served one at a time, progressively specific
  - The three ranking functions differ only in how they treat ties.

solutionSql: |
  ...

explanation:
  lineByLine:                  # note the field is `clause`, not `sql`
    - clause: "dense_rank() over (order by salary desc)"
      note: Numbers distinct salary values from the top.
  walkthrough: |
    Traced against the real sample rows.
  alternatives:                # OPTIONAL
    - title: OFFSET over distinct salaries
      sql: |
        ...
      when: When window functions are unavailable.
      equivalent: true         # recorded by running both, not asserted
  performance: |
    What it costs and how it scales.
  wrongApproaches:             # answers that look right; the sql IS executed
    - title: "ORDER BY salary DESC LIMIT 1 OFFSET 1"
      sql: |
        ...
      whyWrong: Skips the second row, not the second distinct value.
  extraTests:                  # additional checks, usually on learning_alt
    - name: Ties at the top
      schema: learning_alt
      note: Two employees share the top salary here.
      sql: |
        ...
  followUps:                   # what the interviewer asks next
    - question: How would you generalise this to the Nth highest?
      answer: ...
  related: [w-ranking]         # lesson or problem ids, must resolve
```

Three things about this are easy to get wrong:

**`lineByLine` uses `clause`, not `sql`.** The recorder treats every `sql`
field it finds as a statement to execute, and `where rnk = 2` is a fragment.
Naming it differently is what keeps "every `sql` field is executed" simple
enough to be true.

**`wrongApproaches[].sql` is executed.** A wrong approach is semantically
wrong, not syntactically broken — the point is to show what it actually
returns. If it will not run, it belongs in the prose instead.

**`source` is all-or-nothing.** A half-filled citation cannot be checked,
which is worse than none. A problem with no public origin is left
unattributed rather than given an invented one.

## Quiz questions

```yaml
- id: q-null-not-in
  topic: NULL handling         # groups the accuracy report
  difficulty: Medium           # Easy | Medium | Hard
  question: >-
    `SELECT * FROM t WHERE x NOT IN (SELECT y FROM s)` returns no rows...
  options:
    - Some row of `s` has a NULL `y`
    - The subquery returns too many rows for NOT IN
  answerIndex: 0               # zero-based, checked to be in range
  explanation: >-
    Why the right answer is right and, where the distractors are the
    interesting part, why the others are wrong.
```

Write distractors somebody would actually pick. An option nobody would choose
teaches nothing and makes the question a formality.

Anything that can be settled by executing SQL belongs in a lesson example or
a problem instead, where the learner can watch it being settled.

## Writing the SQL

**It must be a single read-only `SELECT`.** The playground refuses anything
else, and the recorder runs through the playground — so a statement the
playground would reject cannot be recorded. That is the feature: a lesson
cannot teach something a learner is unable to try.

The consequence is that `EXPLAIN`, DDL and DML belong in prose, not in a
runnable example. The performance and migrations lessons are written that way.

**Avoid anything whose answer changes.** No `current_date`, no `now()`, no
`random()`. The output is recorded once and compared forever; a query whose
answer moves with the clock cannot have one. Use a literal date.

**Two known parser limitations.** The validator uses JSqlParser, which does
not accept `AS MATERIALIZED` on a CTE or the `@?` / `@@` jsonpath operators.
Use `jsonb_path_exists()` and `jsonb_path_match()` instead, and discuss
`MATERIALIZED` in prose. Both are noted where they come up in the lessons.

**Reserved words make poor aliases.** `as both` fails to parse; `as
actual_overlap` does not.

## Checking your work before you commit

```bash
bash scripts/record-sql.sh     # fails, loudly, if any statement does not run
cd backend && ./mvnw test      # includes "every reference solution is CORRECT"
git diff -- '*expected-output.json'
```

That last one matters. A changed recorded output is either a content change
you made or a dataset change you did not expect, and the second is worth
stopping for.

-- Progress tracking and spaced revision.
--
-- WHO A LEARNER IS
-- ----------------
-- This application has no authentication, because it is a single-person study
-- tool that runs on a laptop. Progress is therefore keyed on a `learner_id`
-- text column supplied by the client and defaulting to 'local'. That is a
-- deliberate, bounded decision rather than an oversight:
--
--   * it keeps every progress row attributable, so adding real accounts later
--     means populating the column differently rather than reshaping the
--     schema;
--   * it lets one person keep two separate attempts at the course — a dry run
--     and a real one — without deleting anything;
--   * and it is honest about what it is. A learner_id is not a credential and
--     nothing here is protected by it. Anyone who can reach the API can read
--     and write any learner's progress, which is acceptable for a tool bound
--     to localhost and would not be for anything else.
--
-- WHY THESE TABLES ARE WRITTEN AND THE CURRICULUM TABLES ARE NOT
-- ---------------------------------------------------------------
-- Everything in V1 is a projection of the YAML files and is deleted and
-- rebuilt on every startup. These four tables are the opposite: they are the
-- only durable state the application has, and CurriculumLoader must never
-- touch them. That is why they reference the content tables with ON DELETE
-- CASCADE *removed* — a content reload that dropped a lesson would otherwise
-- silently delete the learner's history of it.
--
-- Instead the references are soft: lesson_id and problem_id are plain text
-- with no foreign key. A row for content that no longer exists is harmless and
-- is filtered out on read, and keeping it means renaming a lesson id does not
-- destroy the record of having studied it.

-- ------------------------------------------------------------- lessons ----
create table app.lesson_progress (
    learner_id     text        not null default 'local',
    lesson_id      text        not null,
    status         text        not null default 'in_progress'
                   check (status in ('in_progress', 'completed')),
    -- 1 to 5, self-reported. Drives the revision schedule: a lesson you felt
    -- shaky on comes back sooner than one you were sure of.
    confidence     integer     check (confidence between 1 and 5),
    first_viewed_at timestamptz not null default now(),
    last_viewed_at timestamptz not null default now(),
    completed_at   timestamptz,
    view_count     integer     not null default 1 check (view_count > 0),
    primary key (learner_id, lesson_id),
    -- completed_at and status must agree. A CHECK is the right place for this
    -- because the two columns are written by several code paths and the
    -- invariant has to hold for all of them.
    constraint completed_has_a_timestamp
        check ((status = 'completed') = (completed_at is not null))
);

create index idx_lesson_progress_learner on app.lesson_progress (learner_id, status);

-- ------------------------------------------------------------ problems ----
-- One row per submission, not one per problem. The history is the point: "I
-- got this wrong twice before getting it right" is the information a revision
-- schedule needs, and an upsert that overwrote the previous attempt would
-- throw it away.
create table app.problem_attempt (
    id            bigint      generated always as identity primary key,
    learner_id    text        not null default 'local',
    problem_id    text        not null,
    submitted_sql text        not null,
    verdict       text        not null
                  check (verdict in ('CORRECT', 'CORRECT_ON_SAMPLE_ONLY',
                                     'INCORRECT', 'DID_NOT_RUN', 'REFUSED')),
    correct       boolean     not null,
    hints_used    integer     not null default 0 check (hints_used >= 0),
    attempted_at  timestamptz not null default now()
);

create index idx_problem_attempt_learner on app.problem_attempt (learner_id, problem_id, attempted_at desc);

-- ---------------------------------------------------------------- quiz ----
create table app.quiz_attempt (
    id           bigint      generated always as identity primary key,
    learner_id   text        not null default 'local',
    question_id  text        not null,
    chosen_index integer     not null check (chosen_index >= 0),
    correct      boolean     not null,
    attempted_at timestamptz not null default now()
);

create index idx_quiz_attempt_learner on app.quiz_attempt (learner_id, question_id, attempted_at desc);

-- ------------------------------------------------------------ revision ----
-- A deliberately small spaced-repetition scheduler, close to SM-2.
--
-- The interesting column is `ease`. It starts at 2.5 and moves with the
-- quality of each review, so an item you keep getting wrong has its interval
-- grow slowly and one you find easy grows fast. Storing it per item rather
-- than using a fixed ladder is what makes the schedule adapt; without it this
-- is just a timer.
--
-- `lapses` is kept separately from `reviews` because the two answer different
-- questions: how much practice has this had, and how often has it collapsed.
-- A high-lapse item is a candidate for rereading the lesson rather than for
-- another review.
create table app.revision_item (
    learner_id      text        not null default 'local',
    item_kind       text        not null check (item_kind in ('lesson', 'problem', 'quiz')),
    item_id         text        not null,
    due_on          date        not null default current_date,
    interval_days   integer     not null default 1 check (interval_days > 0),
    ease            numeric(4,2) not null default 2.50 check (ease >= 1.30),
    reviews         integer     not null default 0 check (reviews >= 0),
    lapses          integer     not null default 0 check (lapses >= 0),
    last_reviewed_at timestamptz,
    last_quality    integer     check (last_quality between 0 and 5),
    primary key (learner_id, item_kind, item_id)
);

-- The dominant query is "what is due today", so the index leads with the
-- learner and the due date rather than with the item.
create index idx_revision_due on app.revision_item (learner_id, due_on, item_kind);

comment on table app.lesson_progress is
    'Per-learner lesson state. Durable — never touched by the curriculum reload.';
comment on table app.problem_attempt is
    'Every problem submission, kept as history rather than overwritten.';
comment on table app.quiz_attempt is
    'Every quiz answer, for topic-level weak-spot analysis.';
comment on table app.revision_item is
    'SM-2-style spaced repetition schedule across lessons, problems and quiz topics.';

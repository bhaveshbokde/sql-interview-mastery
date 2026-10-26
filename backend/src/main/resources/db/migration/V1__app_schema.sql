-- Application schema: the curriculum, the problem bank and the quiz bank.
--
-- Nothing in here is ever reachable by the playground. The learner role is
-- granted no privilege on this schema at all, which is what stops someone
-- typing `select * from app.problem_solution` into the editor and reading the
-- answers. That is a privilege boundary enforced by PostgreSQL, not a filter
-- the application has to remember to apply.
--
-- Shape of the model. The columns people search, sort and filter on are real
-- relational columns; the lesson and problem *bodies* live in jsonb. A lesson
-- has fourteen required parts of wildly different shapes (prose, SQL with
-- recorded output, exercise lists, question-and-answer pairs) and modelling
-- each of those as its own table would produce a dozen joins to render one
-- page, with no query ever filtering on them. The JSON files under
-- resources/curriculum are the source of truth; this schema is the queryable
-- projection of them, rebuilt on every startup by CurriculumLoader.

create schema if not exists app;

-- ---------------------------------------------------------------- modules --
create table app.module (
    id          text    primary key,
    position    integer not null,
    title       text    not null,
    slug        text    not null unique,
    summary     text    not null,
    icon        text
);

-- ---------------------------------------------------------------- lessons --
create table app.lesson (
    id                text     primary key,
    module_id         text     not null references app.module(id) on delete cascade,
    position          integer  not null,
    title             text     not null,
    slug              text     not null unique,
    difficulty        text     not null
                      check (difficulty in ('Beginner', 'Intermediate', 'Advanced')),
    estimated_minutes integer  not null check (estimated_minutes > 0),
    summary           text     not null,
    tags              text[]   not null default '{}',
    -- The full lesson body: objectives, prose, syntax, examples with their
    -- recorded output, pitfalls, interview questions, exercises, checklist.
    body              jsonb    not null,
    search_vector     tsvector generated always as (
                          setweight(to_tsvector('english', title), 'A')
                          || setweight(to_tsvector('english', summary), 'B')
                          || setweight(to_tsvector('english', coalesce(body ->> 'plainText', '')), 'C')
                      ) stored
);

create index idx_lesson_module on app.lesson (module_id, position);
create index idx_lesson_search on app.lesson using gin (search_vector);
create index idx_lesson_tags on app.lesson using gin (tags);

-- --------------------------------------------------------------- problems --
create table app.problem (
    id              text     primary key,
    position        integer  not null,
    title           text     not null,
    slug            text     not null unique,
    difficulty      text     not null check (difficulty in ('Easy', 'Medium', 'Hard')),
    topic           text     not null,
    tags            text[]   not null default '{}',
    -- Recorded only when the problem really does come from a public source.
    -- A null here means "a standard pattern, no single canonical source", not
    -- "we forgot"; the loader refuses to invent one.
    source_name     text,
    source_url      text,
    -- Whether row order is part of the answer. Evaluation compares results as
    -- multisets unless this is true, so a correct query is not rejected for
    -- returning the same rows in a different order.
    order_sensitive boolean  not null default false,
    statement       jsonb    not null,
    search_vector   tsvector generated always as (
                        setweight(to_tsvector('english', title), 'A')
                        || setweight(to_tsvector('english', topic), 'B')
                        || setweight(to_tsvector('english', coalesce(statement ->> 'plainText', '')), 'C')
                    ) stored
);

create index idx_problem_difficulty on app.problem (difficulty);
create index idx_problem_topic on app.problem (topic);
create index idx_problem_search on app.problem using gin (search_vector);
create index idx_problem_tags on app.problem using gin (tags);

-- Hints are a separate table rather than an array inside the statement so that
-- the endpoint serving a problem physically cannot return them by accident.
-- Revealing hint 2 requires asking for hint 2.
create table app.problem_hint (
    problem_id text    not null references app.problem(id) on delete cascade,
    ordinal    integer not null check (ordinal > 0),
    hint       text    not null,
    primary key (problem_id, ordinal)
);

-- Likewise the solution. The list and detail endpoints read app.problem; only
-- the explicit reveal endpoint reads this table.
create table app.problem_solution (
    problem_id  text  primary key references app.problem(id) on delete cascade,
    solution_sql text not null,
    explanation  jsonb not null
);

-- ------------------------------------------------------------------ quiz ---
create table app.quiz_question (
    id          text    primary key,
    topic       text    not null,
    difficulty  text    not null check (difficulty in ('Easy', 'Medium', 'Hard')),
    question    text    not null,
    options     jsonb   not null,
    answer_index integer not null check (answer_index >= 0),
    explanation text    not null
);

create index idx_quiz_topic on app.quiz_question (topic);

-- -------------------------------------------------------- load bookkeeping --
-- One row per content load. The checksum is over the curriculum resources, so
-- "did the content I am looking at come from the files currently on disk?" is
-- answerable, and a reload that changed nothing is visible as such.
create table app.content_load (
    id         integer generated always as identity primary key,
    loaded_at  timestamptz not null default now(),
    checksum   text        not null,
    modules    integer     not null,
    lessons    integer     not null,
    problems   integer     not null,
    quiz_items integer     not null
);

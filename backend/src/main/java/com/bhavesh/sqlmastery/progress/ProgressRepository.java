package com.bhavesh.sqlmastery.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the four progress tables.
 *
 * <p>Like {@link com.bhavesh.sqlmastery.curriculum.CurriculumRepository}, every
 * statement here is hand-written with bound parameters. The queries are also
 * deliberately the kind this course teaches — left joins that keep the
 * un-attempted rows, filtered aggregates for the per-status counts, a window
 * function for the per-problem latest attempt — because a learning application
 * whose own SQL contradicts its lessons is not in a position to teach them.
 */
@Repository
public class ProgressRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProgressRepository(JdbcTemplate appJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = appJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ lessons --

    /**
     * Records a lesson view.
     *
     * <p>An upsert rather than a select-then-insert, so two tabs opening the
     * same lesson cannot race. {@code view_count} is incremented in the
     * database rather than read, added to and written back, for the same
     * reason the transactions lesson gives.
     */
    public void recordLessonView(String learnerId, String lessonId) {
        jdbc.update("""
                insert into app.lesson_progress (learner_id, lesson_id, status)
                values (?, ?, 'in_progress')
                on conflict (learner_id, lesson_id) do update
                   set last_viewed_at = now(),
                       view_count     = app.lesson_progress.view_count + 1
                """, learnerId, lessonId);
    }

    /**
     * Sets a lesson's status and confidence.
     *
     * @param completed true to mark complete, false to reopen it
     * @param confidence 1-5, or null to leave whatever was there
     */
    public void setLessonStatus(String learnerId, String lessonId, boolean completed, Integer confidence) {
        jdbc.update("""
                insert into app.lesson_progress
                       (learner_id, lesson_id, status, confidence, completed_at)
                values (?, ?, ?, ?, case when ? then now() end)
                on conflict (learner_id, lesson_id) do update
                   set status         = excluded.status,
                       -- A null confidence means "not supplied", not "clear it".
                       confidence     = coalesce(excluded.confidence, app.lesson_progress.confidence),
                       completed_at   = excluded.completed_at,
                       last_viewed_at = now()
                """,
                learnerId, lessonId, completed ? "completed" : "in_progress", confidence, completed);
    }

    /**
     * Every lesson with its progress, including the ones never opened.
     *
     * <p>The LEFT JOIN is the point: a learner who has opened four lessons
     * should see thirty-six rows, not four.
     */
    public ArrayNode findLessonProgress(String learnerId) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                select l.id,
                       l.module_id,
                       l.title,
                       l.slug,
                       l.difficulty,
                       l.estimated_minutes,
                       l.position,
                       coalesce(p.status, 'not_started') as status,
                       p.confidence,
                       p.view_count,
                       p.last_viewed_at,
                       p.completed_at,
                       r.due_on                          as revision_due_on,
                       r.interval_days                   as revision_interval_days
                from app.lesson l
                left join app.lesson_progress p
                       on p.lesson_id = l.id and p.learner_id = ?
                left join app.revision_item r
                       on r.item_id = l.id and r.item_kind = 'lesson' and r.learner_id = ?
                order by l.module_id, l.position
                """, (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("id", rs.getString("id"));
            node.put("moduleId", rs.getString("module_id"));
            node.put("title", rs.getString("title"));
            node.put("slug", rs.getString("slug"));
            node.put("difficulty", rs.getString("difficulty"));
            node.put("estimatedMinutes", rs.getInt("estimated_minutes"));
            node.put("position", rs.getInt("position"));
            node.put("status", rs.getString("status"));
            putNullableInt(node, "confidence", rs, "confidence");
            putNullableInt(node, "viewCount", rs, "view_count");
            putTimestamp(node, "lastViewedAt", rs, "last_viewed_at");
            putTimestamp(node, "completedAt", rs, "completed_at");
            putDate(node, "revisionDueOn", rs, "revision_due_on");
            putNullableInt(node, "revisionIntervalDays", rs, "revision_interval_days");
        }, learnerId, learnerId);
        return out;
    }

    // ----------------------------------------------------------- problems --

    public void recordProblemAttempt(
            String learnerId, String problemId, String sql,
            String verdict, boolean correct, int hintsUsed) {
        jdbc.update("""
                insert into app.problem_attempt
                       (learner_id, problem_id, submitted_sql, verdict, correct, hints_used)
                values (?, ?, ?, ?, ?, ?)
                """, learnerId, problemId, sql, verdict, correct, hintsUsed);
    }

    /**
     * Every problem with its attempt history summarised.
     *
     * <p>{@code bool_or(correct)} answers "has this ever been solved", which is
     * a different and more useful question than whether the most recent
     * attempt was right — a learner who solved it and then experimented should
     * still see it as solved.
     */
    public ArrayNode findProblemProgress(String learnerId) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                select p.id,
                       p.title,
                       p.slug,
                       p.difficulty,
                       p.topic,
                       p.position,
                       coalesce(a.attempts, 0)                as attempts,
                       coalesce(a.solved, false)              as solved,
                       a.first_solved_at,
                       a.last_attempt_at,
                       a.last_verdict,
                       r.due_on                               as revision_due_on
                from app.problem p
                left join (
                    select problem_id,
                           count(*)                                           as attempts,
                           bool_or(correct)                                   as solved,
                           min(attempted_at) filter (where correct)           as first_solved_at,
                           max(attempted_at)                                  as last_attempt_at,
                           (array_agg(verdict order by attempted_at desc))[1] as last_verdict
                    from app.problem_attempt
                    where learner_id = ?
                    group by problem_id
                ) a on a.problem_id = p.id
                left join app.revision_item r
                       on r.item_id = p.id and r.item_kind = 'problem' and r.learner_id = ?
                order by p.position
                """, (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("id", rs.getString("id"));
            node.put("title", rs.getString("title"));
            node.put("slug", rs.getString("slug"));
            node.put("difficulty", rs.getString("difficulty"));
            node.put("topic", rs.getString("topic"));
            node.put("position", rs.getInt("position"));
            node.put("attempts", rs.getInt("attempts"));
            node.put("solved", rs.getBoolean("solved"));
            putTimestamp(node, "firstSolvedAt", rs, "first_solved_at");
            putTimestamp(node, "lastAttemptAt", rs, "last_attempt_at");
            node.put("lastVerdict", rs.getString("last_verdict"));
            putDate(node, "revisionDueOn", rs, "revision_due_on");
        }, learnerId, learnerId);
        return out;
    }

    /** The most recent attempts at one problem, newest first. */
    public ArrayNode findAttempts(String learnerId, String problemId, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                select a.id, a.submitted_sql, a.verdict, a.correct, a.hints_used, a.attempted_at
                from app.problem_attempt a
                join app.problem p on p.id = a.problem_id
                where a.learner_id = ?
                  and (p.id = ? or p.slug = ?)
                order by a.attempted_at desc
                limit ?
                """, (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("id", rs.getLong("id"));
            node.put("sql", rs.getString("submitted_sql"));
            node.put("verdict", rs.getString("verdict"));
            node.put("correct", rs.getBoolean("correct"));
            node.put("hintsUsed", rs.getInt("hints_used"));
            putTimestamp(node, "attemptedAt", rs, "attempted_at");
        }, learnerId, problemId, problemId, limit);
        return out;
    }

    // --------------------------------------------------------------- quiz --

    public void recordQuizAttempt(String learnerId, String questionId, int chosenIndex, boolean correct) {
        jdbc.update("""
                insert into app.quiz_attempt (learner_id, question_id, chosen_index, correct)
                values (?, ?, ?, ?)
                """, learnerId, questionId, chosenIndex, correct);
    }

    /**
     * Accuracy per quiz topic, worst first.
     *
     * <p>This is the "what am I weakest at" query, and it is the reason the
     * individual attempts are kept rather than a running total.
     */
    public ArrayNode findQuizAccuracyByTopic(String learnerId) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                select q.topic,
                       count(*)                            as answered,
                       count(*) filter (where a.correct)   as correct,
                       count(distinct q.id)                as distinct_questions,
                       round(100.0 * count(*) filter (where a.correct) / count(*), 1) as accuracy_pct
                from app.quiz_attempt a
                join app.quiz_question q on q.id = a.question_id
                where a.learner_id = ?
                group by q.topic
                order by accuracy_pct, q.topic
                """, (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("topic", rs.getString("topic"));
            node.put("answered", rs.getInt("answered"));
            node.put("correct", rs.getInt("correct"));
            node.put("distinctQuestions", rs.getInt("distinct_questions"));
            node.put("accuracyPct", rs.getBigDecimal("accuracy_pct"));
        }, learnerId);
        return out;
    }

    // ----------------------------------------------------------- revision --

    /** Reads one revision item, or null if it has never been scheduled. */
    public RevisionState findRevisionState(String learnerId, String kind, String itemId) {
        return jdbc.query("""
                select interval_days, ease, reviews, lapses
                from app.revision_item
                where learner_id = ? and item_kind = ? and item_id = ?
                """, (RowMapper<RevisionState>) (rs, n) -> new RevisionState(
                        rs.getInt("interval_days"),
                        rs.getBigDecimal("ease").doubleValue(),
                        rs.getInt("reviews"),
                        rs.getInt("lapses")),
                learnerId, kind, itemId).stream().findFirst().orElse(null);
    }

    /** Writes the next due date for an item, creating the row if needed. */
    public void upsertRevision(
            String learnerId, String kind, String itemId,
            int intervalDays, double ease, int reviews, int lapses, int quality) {
        jdbc.update("""
                insert into app.revision_item
                       (learner_id, item_kind, item_id, due_on, interval_days,
                        ease, reviews, lapses, last_reviewed_at, last_quality)
                values (?, ?, ?, current_date + ?, ?, ?, ?, ?, now(), ?)
                on conflict (learner_id, item_kind, item_id) do update
                   set due_on           = excluded.due_on,
                       interval_days    = excluded.interval_days,
                       ease             = excluded.ease,
                       reviews          = excluded.reviews,
                       lapses           = excluded.lapses,
                       last_reviewed_at = excluded.last_reviewed_at,
                       last_quality     = excluded.last_quality
                """,
                learnerId, kind, itemId, intervalDays, intervalDays,
                ease, reviews, lapses, quality);
    }

    /**
     * Items due on or before today, with their titles resolved.
     *
     * <p>A LEFT JOIN to each content table and a COALESCE across them, because
     * an item_id can refer to a lesson, a problem or a quiz question and the
     * revision table deliberately does not know which table to look in. The
     * {@code title is not null} filter is what drops revision rows whose
     * content no longer exists after a curriculum change.
     */
    public ArrayNode findDueRevisions(String learnerId, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                select r.item_kind,
                       r.item_id,
                       r.due_on,
                       r.interval_days,
                       r.ease,
                       r.reviews,
                       r.lapses,
                       r.last_reviewed_at,
                       current_date - r.due_on as days_overdue,
                       coalesce(l.title, p.title, q.question) as title,
                       coalesce(l.slug,  p.slug)              as slug
                from app.revision_item r
                left join app.lesson        l on r.item_kind = 'lesson'  and l.id = r.item_id
                left join app.problem       p on r.item_kind = 'problem' and p.id = r.item_id
                left join app.quiz_question q on r.item_kind = 'quiz'    and q.id = r.item_id
                where r.learner_id = ?
                  and r.due_on <= current_date
                  and coalesce(l.title, p.title, q.question) is not null
                order by r.due_on, r.item_kind, r.item_id
                limit ?
                """, (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("kind", rs.getString("item_kind"));
            node.put("id", rs.getString("item_id"));
            node.put("title", rs.getString("title"));
            node.put("slug", rs.getString("slug"));
            putDate(node, "dueOn", rs, "due_on");
            node.put("intervalDays", rs.getInt("interval_days"));
            node.put("ease", rs.getBigDecimal("ease"));
            node.put("reviews", rs.getInt("reviews"));
            node.put("lapses", rs.getInt("lapses"));
            node.put("daysOverdue", rs.getInt("days_overdue"));
            putTimestamp(node, "lastReviewedAt", rs, "last_reviewed_at");
        }, learnerId, limit);
        return out;
    }

    // -------------------------------------------------------------- stats --

    /** The dashboard figures, in one round trip. */
    public ObjectNode overview(String learnerId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("learnerId", learnerId);

        jdbc.query("""
                select (select count(*) from app.lesson)                                as total_lessons,
                       (select count(*) from app.lesson_progress
                        where learner_id = ? and status = 'completed')                  as lessons_completed,
                       (select count(*) from app.lesson_progress
                        where learner_id = ? and status = 'in_progress')                as lessons_started,
                       (select coalesce(sum(l.estimated_minutes), 0)
                        from app.lesson_progress p join app.lesson l on l.id = p.lesson_id
                        where p.learner_id = ? and p.status = 'completed')              as minutes_completed,
                       (select coalesce(sum(estimated_minutes), 0) from app.lesson)     as minutes_total,
                       (select count(*) from app.problem)                               as total_problems,
                       (select count(distinct problem_id) from app.problem_attempt
                        where learner_id = ? and correct)                               as problems_solved,
                       (select count(*) from app.problem_attempt where learner_id = ?)  as problem_attempts,
                       (select count(*) from app.quiz_attempt where learner_id = ?)     as quiz_answered,
                       (select count(*) from app.quiz_attempt
                        where learner_id = ? and correct)                               as quiz_correct,
                       (select count(*) from app.revision_item
                        where learner_id = ? and due_on <= current_date)                as revisions_due
                """, (RowCallbackHandler) rs -> {
            node.put("totalLessons", rs.getInt("total_lessons"));
            node.put("lessonsCompleted", rs.getInt("lessons_completed"));
            node.put("lessonsStarted", rs.getInt("lessons_started"));
            node.put("minutesCompleted", rs.getInt("minutes_completed"));
            node.put("minutesTotal", rs.getInt("minutes_total"));
            node.put("totalProblems", rs.getInt("total_problems"));
            node.put("problemsSolved", rs.getInt("problems_solved"));
            node.put("problemAttempts", rs.getInt("problem_attempts"));
            node.put("quizAnswered", rs.getInt("quiz_answered"));
            node.put("quizCorrect", rs.getInt("quiz_correct"));
            node.put("revisionsDue", rs.getInt("revisions_due"));
        }, learnerId, learnerId, learnerId, learnerId, learnerId, learnerId, learnerId, learnerId);

        ArrayNode modules = node.putArray("modules");
        jdbc.query("""
                select m.id,
                       m.title,
                       m.position,
                       count(l.id)                                               as lessons,
                       count(p.lesson_id) filter (where p.status = 'completed')  as completed
                from app.module m
                left join app.lesson l on l.module_id = m.id
                left join app.lesson_progress p
                       on p.lesson_id = l.id and p.learner_id = ?
                group by m.id, m.title, m.position
                order by m.position
                """, (RowCallbackHandler) rs -> {
            ObjectNode module = modules.addObject();
            module.put("id", rs.getString("id"));
            module.put("title", rs.getString("title"));
            module.put("position", rs.getInt("position"));
            module.put("lessons", rs.getInt("lessons"));
            module.put("completed", rs.getInt("completed"));
        }, learnerId);

        // Last fourteen days of activity, for a sparkline. generate_series
        // supplies the days with no activity, which GROUP BY cannot invent —
        // the exact pattern the aggregation module teaches.
        ArrayNode activity = node.putArray("recentActivity");
        jdbc.query("""
                select d::date                                     as day,
                       coalesce(a.problems, 0)                     as problems,
                       coalesce(q.quiz, 0)                         as quiz,
                       coalesce(l.lessons, 0)                      as lessons
                from generate_series(current_date - 13, current_date, interval '1 day') as g(d)
                left join (select attempted_at::date as day, count(*) as problems
                           from app.problem_attempt where learner_id = ?
                           group by 1) a on a.day = d::date
                left join (select attempted_at::date as day, count(*) as quiz
                           from app.quiz_attempt where learner_id = ?
                           group by 1) q on q.day = d::date
                left join (select completed_at::date as day, count(*) as lessons
                           from app.lesson_progress
                           where learner_id = ? and completed_at is not null
                           group by 1) l on l.day = d::date
                order by d
                """, (RowCallbackHandler) rs -> {
            ObjectNode day = activity.addObject();
            putDate(day, "day", rs, "day");
            day.put("problems", rs.getInt("problems"));
            day.put("quiz", rs.getInt("quiz"));
            day.put("lessons", rs.getInt("lessons"));
        }, learnerId, learnerId, learnerId);

        return node;
    }

    // -------------------------------------------------------------- reset --

    /** Deletes one learner's progress. Returns how many rows went. */
    public int reset(String learnerId) {
        int n = jdbc.update("delete from app.lesson_progress where learner_id = ?", learnerId);
        n += jdbc.update("delete from app.problem_attempt where learner_id = ?", learnerId);
        n += jdbc.update("delete from app.quiz_attempt where learner_id = ?", learnerId);
        n += jdbc.update("delete from app.revision_item where learner_id = ?", learnerId);
        return n;
    }

    /** @return the canonical lesson id for an id or slug, or null */
    public String resolveLessonId(String idOrSlug) {
        return jdbc.query("select id from app.lesson where id = ? or slug = ?",
                (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug)
                .stream().findFirst().orElse(null);
    }

    /** @return the canonical problem id for an id or slug, or null */
    public String resolveProblemId(String idOrSlug) {
        return jdbc.query("select id from app.problem where id = ? or slug = ?",
                (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug)
                .stream().findFirst().orElse(null);
    }

    /** @return true if the id names a quiz question */
    public boolean quizQuestionExists(String id) {
        return !jdbc.queryForList("select 1 from app.quiz_question where id = ?", id).isEmpty();
    }

    // ------------------------------------------------------------ helpers --

    private static void putNullableInt(ObjectNode node, String name, java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        if (rs.wasNull()) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void putTimestamp(ObjectNode node, String name, java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        node.put(name, value == null ? null : value.toInstant().toString());
    }

    private static void putDate(ObjectNode node, String name, java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        node.put(name, value == null ? null : value.toLocalDate().toString());
    }

    /** The scheduler state for one item. */
    public record RevisionState(int intervalDays, double ease, int reviews, int lapses) {
    }
}

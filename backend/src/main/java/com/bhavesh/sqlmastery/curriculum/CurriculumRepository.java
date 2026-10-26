package com.bhavesh.sqlmastery.curriculum;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads the curriculum back out of the {@code app} schema.
 *
 * <p>Every query here is a hand-written prepared statement with bound
 * parameters. That is partly because this application is about SQL and hiding
 * its own queries behind an ORM would be odd, and partly for a reason worth
 * stating plainly: the filters on this repository — topic, difficulty, tag,
 * free text — are exactly the shape of input that gets concatenated into SQL in
 * real systems, and this file is what the SQL-injection lesson points at when
 * it says "this is what parameterised looks like".
 *
 * <p>The one piece of SQL assembled dynamically is the optional filter list in
 * {@link #findProblems}, and what varies there is the number of {@code ?}
 * placeholders, never a value.
 */
@Repository
public class CurriculumRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CurriculumRepository(JdbcTemplate appJdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = appJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ modules --

    /** Modules in learning order, each with the summary of its lessons. */
    public ArrayNode findModulesWithLessons() {
        ArrayNode modules = objectMapper.createArrayNode();
        jdbc.query("""
                select m.id, m.position, m.title, m.slug, m.summary, m.icon,
                       coalesce(sum(l.estimated_minutes), 0) as total_minutes,
                       count(l.id)                           as lesson_count
                from app.module m
                left join app.lesson l on l.module_id = m.id
                group by m.id, m.position, m.title, m.slug, m.summary, m.icon
                order by m.position
                """, (RowCallbackHandler) rs -> {
            ObjectNode module = modules.addObject();
            module.put("id", rs.getString("id"));
            module.put("position", rs.getInt("position"));
            module.put("title", rs.getString("title"));
            module.put("slug", rs.getString("slug"));
            module.put("summary", rs.getString("summary"));
            module.put("icon", rs.getString("icon"));
            module.put("lessonCount", rs.getInt("lesson_count"));
            module.put("totalMinutes", rs.getInt("total_minutes"));
            module.set("lessons", objectMapper.createArrayNode());
        });

        jdbc.query(LESSON_SUMMARY_SQL + " order by l.module_id, l.position", (RowCallbackHandler) rs -> {
            String moduleId = rs.getString("module_id");
            for (JsonNode module : modules) {
                if (module.get("id").asText().equals(moduleId)) {
                    ((ArrayNode) module.get("lessons")).add(lessonSummary(rs));
                    return;
                }
            }
        });
        return modules;
    }

    // ------------------------------------------------------------ lessons --

    private static final String LESSON_SUMMARY_SQL = """
            select l.id, l.module_id, l.position, l.title, l.slug, l.difficulty,
                   l.estimated_minutes, l.summary, l.tags
            from app.lesson l
            """;

    public ArrayNode findAllLessonSummaries() {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query(LESSON_SUMMARY_SQL + " order by l.module_id, l.position",
                (RowCallbackHandler) rs -> out.add(lessonSummary(rs)));
        return out;
    }

    /** @param idOrSlug either works, so links can be readable and ids stable */
    public Optional<JsonNode> findLesson(String idOrSlug) {
        List<String> bodies = jdbc.query(
                "select body from app.lesson where id = ? or slug = ?",
                (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug);
        return bodies.stream().findFirst().map(this::readJson);
    }

    // ----------------------------------------------------------- problems --

    private static final String PROBLEM_SUMMARY_SQL = """
            select p.id, p.position, p.title, p.slug, p.difficulty, p.topic, p.tags,
                   p.source_name, p.source_url, p.order_sensitive
            from app.problem p
            """;

    /**
     * @param topic      exact match, or null for all
     * @param difficulty exact match, or null for all
     * @param tag        must be present in the problem's tags, or null
     * @param query      free text; matched against the full-text index
     */
    public ArrayNode findProblems(String topic, String difficulty, String tag, String query) {
        StringBuilder sql = new StringBuilder(PROBLEM_SUMMARY_SQL);
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (topic != null && !topic.isBlank()) {
            conditions.add("p.topic = ?");
            args.add(topic);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            conditions.add("p.difficulty = ?");
            args.add(difficulty);
        }
        if (tag != null && !tag.isBlank()) {
            conditions.add("? = any(p.tags)");
            args.add(tag);
        }
        if (query != null && !query.isBlank()) {
            conditions.add("p.search_vector @@ websearch_to_tsquery('english', ?)");
            args.add(query);
        }
        if (!conditions.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", conditions));
        }
        sql.append(" order by p.position");

        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query(sql.toString(), args.toArray(), (RowCallbackHandler) rs -> out.add(problemSummary(rs)));
        return out;
    }

    /** The problem without its hints or its solution — those are separate tables. */
    public Optional<JsonNode> findProblem(String idOrSlug) {
        List<String> rows = jdbc.query(
                "select statement from app.problem where id = ? or slug = ?",
                (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug);
        return rows.stream().findFirst().map(this::readJson);
    }

    public List<String> findHints(String idOrSlug) {
        return jdbc.query("""
                select h.hint
                from app.problem_hint h
                join app.problem p on p.id = h.problem_id
                where p.id = ? or p.slug = ?
                order by h.ordinal
                """, (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug);
    }

    public Optional<JsonNode> findSolution(String idOrSlug) {
        List<ObjectNode> rows = jdbc.query("""
                select s.solution_sql, s.explanation
                from app.problem_solution s
                join app.problem p on p.id = s.problem_id
                where p.id = ? or p.slug = ?
                """, (RowMapper<ObjectNode>) (rs, n) -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("sql", rs.getString("solution_sql"));
            node.set("explanation", readJson(rs.getString("explanation")));
            return node;
        }, idOrSlug, idOrSlug);
        return rows.stream().findFirst().map(n -> n);
    }

    /** The reference answer alone, for evaluating a submission. */
    public Optional<String> findSolutionSql(String idOrSlug) {
        return jdbc.query("""
                select s.solution_sql
                from app.problem_solution s
                join app.problem p on p.id = s.problem_id
                where p.id = ? or p.slug = ?
                """, (RowMapper<String>) (rs, n) -> rs.getString(1), idOrSlug, idOrSlug)
                .stream().findFirst();
    }

    /** @return the problem's id, difficulty, order sensitivity and expected columns */
    public Optional<ProblemMeta> findProblemMeta(String idOrSlug) {
        return jdbc.query("""
                select p.id, p.title, p.order_sensitive, p.statement -> 'expectedColumns' as cols
                from app.problem p
                where p.id = ? or p.slug = ?
                """, (RowMapper<ProblemMeta>) (rs, n) -> {
            List<String> columns = new ArrayList<>();
            JsonNode node = readJson(rs.getString("cols"));
            node.forEach(c -> columns.add(c.asText()));
            return new ProblemMeta(rs.getString("id"), rs.getString("title"),
                    rs.getBoolean("order_sensitive"), columns);
        }, idOrSlug, idOrSlug).stream().findFirst();
    }

    public List<String> findProblemTopics() {
        return jdbc.queryForList("select distinct topic from app.problem order by 1", String.class);
    }

    // --------------------------------------------------------------- quiz --

    /** Questions without their answers. The answer is checked server-side. */
    public ArrayNode findQuizQuestions(String topic, String difficulty, int limit) {
        StringBuilder sql = new StringBuilder(
                "select id, topic, difficulty, question, options from app.quiz_question");
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (topic != null && !topic.isBlank()) {
            conditions.add("topic = ?");
            args.add(topic);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            conditions.add("difficulty = ?");
            args.add(difficulty);
        }
        if (!conditions.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", conditions));
        }
        // Ordered by a hash of the id rather than random(), so the same request
        // gives the same paper. A quiz that reshuffles on every refresh makes
        // "I got question 3 wrong" meaningless.
        sql.append(" order by md5(id) limit ?");
        args.add(limit);

        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query(sql.toString(), args.toArray(), (RowCallbackHandler) rs -> {
            ObjectNode node = out.addObject();
            node.put("id", rs.getString("id"));
            node.put("topic", rs.getString("topic"));
            node.put("difficulty", rs.getString("difficulty"));
            node.put("question", rs.getString("question"));
            node.set("options", readJson(rs.getString("options")));
        });
        return out;
    }

    /** @return the correct index and the explanation, for one question */
    public Optional<QuizAnswer> findQuizAnswer(String id) {
        return jdbc.query(
                "select id, answer_index, explanation, options from app.quiz_question where id = ?",
                (RowMapper<QuizAnswer>) (rs, n) -> new QuizAnswer(
                        rs.getString("id"), rs.getInt("answer_index"),
                        rs.getString("explanation"), readJson(rs.getString("options"))),
                id).stream().findFirst();
    }

    public List<String> findQuizTopics() {
        return jdbc.queryForList("select distinct topic from app.quiz_question order by 1", String.class);
    }

    // ------------------------------------------------------------- search --

    /**
     * One ranked list across lessons and problems.
     *
     * <p>{@code websearch_to_tsquery} rather than {@code plainto_tsquery} so
     * that quoted phrases and {@code or} work the way someone expects from a
     * search box, and so that a stray apostrophe is not a syntax error.
     */
    public ArrayNode search(String query, int limit) {
        ArrayNode out = objectMapper.createArrayNode();
        jdbc.query("""
                (select 'lesson' as kind, l.id, l.title, l.slug, l.summary as blurb,
                        l.difficulty,
                        ts_rank(l.search_vector, websearch_to_tsquery('english', ?)) as rank
                 from app.lesson l
                 where l.search_vector @@ websearch_to_tsquery('english', ?))
                union all
                (select 'problem', p.id, p.title, p.slug, p.topic,
                        p.difficulty,
                        ts_rank(p.search_vector, websearch_to_tsquery('english', ?))
                 from app.problem p
                 where p.search_vector @@ websearch_to_tsquery('english', ?))
                order by rank desc, title
                limit ?
                """,
                ps -> {
                    ps.setString(1, query);
                    ps.setString(2, query);
                    ps.setString(3, query);
                    ps.setString(4, query);
                    ps.setInt(5, limit);
                },
                rs -> {
                    ObjectNode node = out.addObject();
                    node.put("kind", rs.getString("kind"));
                    node.put("id", rs.getString("id"));
                    node.put("title", rs.getString("title"));
                    node.put("slug", rs.getString("slug"));
                    node.put("blurb", rs.getString("blurb"));
                    node.put("difficulty", rs.getString("difficulty"));
                    node.put("rank", rs.getDouble("rank"));
                });
        return out;
    }

    /** Totals for the dashboard. */
    public ObjectNode statistics() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modules", count("app.module"));
        node.put("lessons", count("app.lesson"));
        node.put("problems", count("app.problem"));
        node.put("quizQuestions", count("app.quiz_question"));
        ObjectNode byDifficulty = node.putObject("problemsByDifficulty");
        jdbc.query("select difficulty, count(*) as n from app.problem group by difficulty",
                (RowCallbackHandler) rs -> byDifficulty.put(rs.getString("difficulty"), rs.getInt("n")));
        ObjectNode byTopic = node.putObject("problemsByTopic");
        jdbc.query("select topic, count(*) as n from app.problem group by topic order by topic",
                (RowCallbackHandler) rs -> byTopic.put(rs.getString("topic"), rs.getInt("n")));
        jdbc.query("""
                select checksum, loaded_at from app.content_load order by id desc limit 1
                """, (RowCallbackHandler) rs -> {
            node.put("contentChecksum", rs.getString("checksum"));
            node.put("contentLoadedAt", String.valueOf(rs.getTimestamp("loaded_at").toInstant()));
        });
        return node;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ helpers --

    private ObjectNode lessonSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", rs.getString("id"));
        node.put("moduleId", rs.getString("module_id"));
        node.put("position", rs.getInt("position"));
        node.put("title", rs.getString("title"));
        node.put("slug", rs.getString("slug"));
        node.put("difficulty", rs.getString("difficulty"));
        node.put("estimatedMinutes", rs.getInt("estimated_minutes"));
        node.put("summary", rs.getString("summary"));
        node.set("tags", toArrayNode(rs.getArray("tags")));
        return node;
    }

    private ObjectNode problemSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", rs.getString("id"));
        node.put("position", rs.getInt("position"));
        node.put("title", rs.getString("title"));
        node.put("slug", rs.getString("slug"));
        node.put("difficulty", rs.getString("difficulty"));
        node.put("topic", rs.getString("topic"));
        node.set("tags", toArrayNode(rs.getArray("tags")));
        node.put("orderSensitive", rs.getBoolean("order_sensitive"));
        String sourceName = rs.getString("source_name");
        if (sourceName != null) {
            ObjectNode source = node.putObject("source");
            source.put("name", sourceName);
            source.put("url", rs.getString("source_url"));
        }
        return node;
    }

    private ArrayNode toArrayNode(java.sql.Array array) throws java.sql.SQLException {
        ArrayNode out = objectMapper.createArrayNode();
        if (array == null) {
            return out;
        }
        Object raw = array.getArray();
        if (raw instanceof Object[] values) {
            for (Object value : values) {
                out.add(String.valueOf(value));
            }
        }
        array.free();
        return out;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored content is not valid JSON", e);
        }
    }

    /** What evaluation needs to know about a problem. */
    public record ProblemMeta(String id, String title, boolean orderSensitive, List<String> expectedColumns) {
    }

    /** The answer to one quiz question. */
    public record QuizAnswer(String id, int answerIndex, String explanation, JsonNode options) {
    }
}

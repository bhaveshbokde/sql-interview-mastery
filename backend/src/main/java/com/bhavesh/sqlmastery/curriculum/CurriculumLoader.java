package com.bhavesh.sqlmastery.curriculum;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.bhavesh.sqlmastery.curriculum.model.Lesson;
import com.bhavesh.sqlmastery.curriculum.model.Module;
import com.bhavesh.sqlmastery.curriculum.model.Problem;
import com.bhavesh.sqlmastery.curriculum.model.QuizQuestion;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the curriculum from YAML and projects it into the {@code app} schema.
 *
 * <p>The files are the source of truth and the database is a rebuildable
 * projection of them: every startup clears the tables and reloads. That rules
 * out the failure mode where the database holds content nobody can find the
 * source of, and makes the content workflow "edit a file, restart" with no
 * migration step in between.
 *
 * <p>Three things happen between reading and writing:
 *
 * <ol>
 *   <li>{@link CurriculumValidator} refuses incomplete content.</li>
 *   <li>{@link ResultInjector} attaches the recorded output of every query, and
 *       refuses content whose queries have never been executed.</li>
 *   <li>Hints and solutions are separated out of the problem body and written
 *       to their own tables, so the endpoint that serves a problem has nothing
 *       to leak.</li>
 * </ol>
 *
 * <p>The whole load is one transaction. A failure halfway through leaves the
 * previous content in place rather than an application serving half a course.
 */
@Service
public class CurriculumLoader {

    private static final Logger log = LoggerFactory.getLogger(CurriculumLoader.class);

    private static final String MODULES = "classpath:curriculum/modules.yaml";
    private static final String LESSONS = "classpath:curriculum/lessons/**/*.yaml";
    private static final String PROBLEMS = "classpath:curriculum/problems/*.yaml";
    private static final String QUIZ = "classpath:curriculum/quiz.yaml";

    private final JdbcTemplate jdbc;
    private final CurriculumValidator validator;
    private final ResultInjector resultInjector;
    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public CurriculumLoader(
            JdbcTemplate appJdbcTemplate,
            CurriculumValidator validator,
            ResultInjector resultInjector,
            ObjectMapper objectMapper) {
        this.jdbc = appJdbcTemplate;
        this.validator = validator;
        this.resultInjector = resultInjector;
        // copyWith rather than a fresh ObjectMapper: the records in
        // curriculum.model are deserialised by constructor parameter name,
        // which needs the ParameterNamesModule that Spring Boot has already
        // registered on the injected mapper. A bare new ObjectMapper() would
        // parse the YAML and then fail to bind a single field.
        // FAIL_ON_UNKNOWN_PROPERTIES is enabled deliberately, and against
        // Spring Boot's default of false. A key in the YAML that no record
        // component matches is a typo or a field somebody expected to exist;
        // ignoring it means the content silently does something other than
        // what it says. That has already happened once here — a `schema:
        // learning_alt` on a lesson example was dropped, and the example ran
        // against the wrong dataset while claiming otherwise. A failed
        // startup naming the property is the cheaper outcome.
        this.yamlMapper = objectMapper.copyWith(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // Nulls are kept on purpose. ResultInjector decides where to attach a
        // recorded result by looking at which output field the record
        // declared, and a field omitted for being null cannot be seen.
        this.jsonMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    /**
     * Parses the YAML and checks it is complete, without touching the database.
     *
     * <p>Separate from {@link #load()} because the recorder needs the content
     * before any output has been recorded for it, and because reading the
     * files is the part worth unit-testing on its own.
     */
    public Content read() {
        List<Module> modules = readAll(MODULES, new TypeReference<List<Module>>() { }).stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingInt(Module::position))
                .toList();
        List<Lesson> lessons = readEach(LESSONS, Lesson.class).stream()
                .sorted(Comparator.comparing(Lesson::moduleId).thenComparingInt(Lesson::position))
                .toList();
        List<Problem> problems = readEach(PROBLEMS, Problem.class).stream()
                .sorted(Comparator.comparingInt(Problem::position))
                .toList();
        List<QuizQuestion> quiz = readAll(QUIZ, new TypeReference<List<QuizQuestion>>() { }).stream()
                .flatMap(List::stream)
                .toList();
        validator.validate(modules, lessons, problems, quiz);
        return new Content(modules, lessons, problems, quiz);
    }

    /** Serialises a lesson or problem with nulls kept, ready for result injection. */
    public ObjectNode toTree(Object content) {
        return jsonMapper.valueToTree(content);
    }

    /** The parsed curriculum. */
    public record Content(
            List<Module> modules,
            List<Lesson> lessons,
            List<Problem> problems,
            List<QuizQuestion> quiz) {
    }

    @Transactional
    public LoadSummary load() {
        Content content = read();
        List<Module> modules = content.modules();
        List<Lesson> lessons = content.lessons();
        List<Problem> problems = content.problems();
        List<QuizQuestion> quiz = content.quiz();

        List<String> missingOutputs = new ArrayList<>();
        List<ObjectNode> lessonBodies = new ArrayList<>(lessons.size());
        for (Lesson lesson : lessons) {
            ObjectNode body = jsonMapper.valueToTree(lesson);
            missingOutputs.addAll(resultInjector.inject(body, "lesson " + lesson.id()));
            body.put("plainText", plainText(body));
            lessonBodies.add(body);
        }
        List<ObjectNode> problemBodies = new ArrayList<>(problems.size());
        for (Problem problem : problems) {
            ObjectNode body = jsonMapper.valueToTree(problem);
            missingOutputs.addAll(resultInjector.inject(body, "problem " + problem.id()));
            problemBodies.add(body);
        }
        if (!missingOutputs.isEmpty()) {
            throw new MissingRecordedOutputException(missingOutputs);
        }

        replaceAll(modules, lessons, lessonBodies, problems, problemBodies, quiz);

        String checksum = checksum(lessonBodies, problemBodies);
        jdbc.update("""
                insert into app.content_load (checksum, modules, lessons, problems, quiz_items)
                values (?, ?, ?, ?, ?)
                """, checksum, modules.size(), lessons.size(), problems.size(), quiz.size());

        LoadSummary summary = new LoadSummary(
                modules.size(), lessons.size(), problems.size(), quiz.size(), checksum);
        log.info("Loaded curriculum: {} modules, {} lessons, {} problems, {} quiz questions (checksum {})",
                summary.modules(), summary.lessons(), summary.problems(), summary.quizItems(),
                checksum.substring(0, 12));
        return summary;
    }

    private void replaceAll(
            List<Module> modules,
            List<Lesson> lessons,
            List<ObjectNode> lessonBodies,
            List<Problem> problems,
            List<ObjectNode> problemBodies,
            List<QuizQuestion> quiz) {

        // Order matters: the children reference the parents.
        jdbc.update("delete from app.problem_solution");
        jdbc.update("delete from app.problem_hint");
        jdbc.update("delete from app.problem");
        jdbc.update("delete from app.lesson");
        jdbc.update("delete from app.module");
        jdbc.update("delete from app.quiz_question");

        for (Module module : modules) {
            jdbc.update("""
                    insert into app.module (id, position, title, slug, summary, icon)
                    values (?, ?, ?, ?, ?, ?)
                    """, module.id(), module.position(), module.title(),
                    module.slug(), module.summary(), module.icon());
        }

        for (int i = 0; i < lessons.size(); i++) {
            Lesson lesson = lessons.get(i);
            jdbc.update("""
                    insert into app.lesson
                        (id, module_id, position, title, slug, difficulty,
                         estimated_minutes, summary, tags, body)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    lesson.id(), lesson.moduleId(), lesson.position(), lesson.title(),
                    lesson.slug(), lesson.difficulty(), lesson.estimatedMinutes(),
                    lesson.summary(), toArray(lesson.tags()), lessonBodies.get(i).toString());
        }

        for (int i = 0; i < problems.size(); i++) {
            Problem problem = problems.get(i);
            ObjectNode body = problemBodies.get(i);
            // The answer is taken out of the body before the body is stored.
            // What remains in app.problem is only what a learner is allowed to
            // see before they have asked for help.
            //
            // "expected" is in that list and is the one that is easy to miss.
            // It is not the solution's text but its recorded output — the
            // answer as a table — so leaving it in would mean the problem page
            // shipped the rows the learner is being asked to produce. It is
            // re-attached to the explanation below, which only the solution
            // endpoint reads.
            ObjectNode statement = body.deepCopy();
            statement.remove(List.of("hints", "solutionSql", "explanation", "expected"));
            statement.put("plainText", plainText(statement));

            jdbc.update("""
                    insert into app.problem
                        (id, position, title, slug, difficulty, topic, tags,
                         source_name, source_url, order_sensitive, statement)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    problem.id(), problem.position(), problem.title(), problem.slug(),
                    problem.difficulty(), problem.topic(), toArray(problem.tags()),
                    problem.source() == null ? null : problem.source().name(),
                    problem.source() == null ? null : problem.source().url(),
                    problem.orderSensitive(), statement.toString());

            List<String> hints = problem.hints();
            for (int h = 0; h < hints.size(); h++) {
                jdbc.update("insert into app.problem_hint (problem_id, ordinal, hint) values (?, ?, ?)",
                        problem.id(), h + 1, hints.get(h));
            }

            ObjectNode explanation = (ObjectNode) body.get("explanation");
            // The recorded output of the solution lives on the problem body,
            // but it belongs with the solution: it is the answer.
            explanation.set("expected", body.get("expected"));
            jdbc.update("""
                    insert into app.problem_solution (problem_id, solution_sql, explanation)
                    values (?, ?, ?::jsonb)
                    """, problem.id(), problem.solutionSql(), explanation.toString());
        }

        for (QuizQuestion question : quiz) {
            jdbc.update("""
                    insert into app.quiz_question
                        (id, topic, difficulty, question, options, answer_index, explanation)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    question.id(), question.topic(), question.difficulty(), question.question(),
                    writeJson(question.options()), question.answerIndex(), question.explanation());
        }
    }

    /**
     * Flattens the prose of a document into one string for full-text search.
     *
     * <p>Only textual leaves are taken, and SQL is skipped: searching for
     * "select" should not return every lesson in the course.
     */
    private String plainText(JsonNode node) {
        StringBuilder out = new StringBuilder();
        collectText(node, out);
        return out.toString();
    }

    private void collectText(JsonNode node, StringBuilder out) {
        if (node.isTextual()) {
            out.append(node.asText()).append(' ');
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String name = entry.getKey();
                if (name.equals("sql") || name.equals("solutionSql")
                        || name.equals("result") || name.equals("expected")
                        || name.equals("plainText")) {
                    return;
                }
                collectText(entry.getValue(), out);
            });
        }
    }

    private <T> List<T> readEach(String pattern, Class<T> type) {
        List<T> items = new ArrayList<>();
        for (Resource resource : resolve(pattern)) {
            try (InputStream in = resource.getInputStream()) {
                items.add(yamlMapper.readValue(in, type));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Could not read " + resource.getFilename() + ": " + e.getMessage(), e);
            }
        }
        return items;
    }

    private <T> List<T> readAll(String pattern, TypeReference<T> type) {
        List<T> items = new ArrayList<>();
        for (Resource resource : resolve(pattern)) {
            try (InputStream in = resource.getInputStream()) {
                items.add(yamlMapper.readValue(in, type));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Could not read " + resource.getFilename() + ": " + e.getMessage(), e);
            }
        }
        return items;
    }

    private Resource[] resolve(String pattern) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
            // Sorted by filename so the load order is the same everywhere; a
            // filesystem's directory order is not a specification.
            java.util.Arrays.sort(resources, Comparator.comparing(r -> String.valueOf(r.getFilename())));
            return resources;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + pattern, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialise curriculum content", e);
        }
    }

    private static String[] toArray(List<String> values) {
        return values == null ? new String[0] : values.toArray(String[]::new);
    }

    /**
     * A digest of the loaded content, so "is the database showing the files
     * currently on disk?" has an answer.
     */
    private String checksum(List<ObjectNode> lessons, List<ObjectNode> problems) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            lessons.forEach(n -> digest.update(n.toString().getBytes(StandardCharsets.UTF_8)));
            problems.forEach(n -> digest.update(n.toString().getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** What was loaded. */
    public record LoadSummary(int modules, int lessons, int problems, int quizItems, String checksum) {
    }

    /** Thrown when content contains a query that has never been executed. */
    public static class MissingRecordedOutputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> missing;

        public MissingRecordedOutputException(List<String> missing) {
            super(missing.size() + " statements in the curriculum have no recorded output. "
                  + "Run `python3 tools/verify_sql.py` to execute them against the database "
                  + "and record what they actually return. They are never written by hand."
                  + System.lineSeparator() + "  - "
                  + String.join(System.lineSeparator() + "  - ", missing));
            this.missing = List.copyOf(missing);
        }

        public List<String> missing() {
            return missing;
        }
    }
}

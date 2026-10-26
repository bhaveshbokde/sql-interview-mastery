package com.bhavesh.sqlmastery.curriculum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.bhavesh.sqlmastery.playground.QueryExecutionService;
import com.bhavesh.sqlmastery.playground.SqlRejectedException;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Executes every statement in the curriculum and records what it returned.
 *
 * <p>Run with:
 *
 * <pre>bash scripts/record-sql.sh</pre>
 *
 * <p>which starts the application under the {@code record} profile. That
 * profile swaps out the normal startup — the usual loader refuses to run when
 * any statement has no recorded output, which would make recording them for
 * the first time impossible.
 *
 * <p>Two properties of how this works matter more than the mechanism:
 *
 * <ol>
 *   <li><b>It runs through {@link QueryExecutionService}</b>, the same service
 *       that serves the editor, under the same least-privileged role and the
 *       same read-only transaction. So the recording is also a check that every
 *       example in the course is a query a learner could actually run. A lesson
 *       cannot teach something the playground forbids without the recording
 *       step failing.</li>
 *   <li><b>Nothing is written by hand.</b> The output of an example is whatever
 *       PostgreSQL returned, which is the only way to be sure a tutorial does
 *       not show a result table its own query does not produce. That mistake is
 *       easy to make, invisible to review, and corrosive — a reader who follows
 *       the example and gets different output concludes they have
 *       misunderstood.</li>
 * </ol>
 *
 * <p>Recorded durations are written as zero. The file is committed, and a
 * timing that changed on every run would produce a diff on every run and tempt
 * someone to stop reading them.
 */
@Component
@Profile("record")
public class CurriculumSqlRecorder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CurriculumSqlRecorder.class);

    private final CurriculumLoader loader;
    private final QueryExecutionService executionService;
    private final ObjectWriter writer;
    private final Path output;

    public CurriculumSqlRecorder(
            CurriculumLoader loader,
            QueryExecutionService executionService,
            ObjectMapper objectMapper,
            @Value("${sqlmastery.curriculum.record-to:src/main/resources/curriculum/expected-output.json}")
            String output) {
        this.loader = loader;
        this.executionService = executionService;
        this.writer = objectMapper.writerWithDefaultPrettyPrinter();
        this.output = Path.of(output);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        CurriculumLoader.Content content = loader.read();

        List<CurriculumSql.Statement> statements = new ArrayList<>();
        content.lessons().forEach(lesson -> statements.addAll(
                CurriculumSql.statements(loader.toTree(lesson))));
        content.problems().forEach(problem -> statements.addAll(
                CurriculumSql.statements(loader.toTree(problem))));

        // Sorted by key so the committed file has a stable order and a content
        // change produces a diff proportional to the change.
        Map<String, QueryResult> recorded = new TreeMap<>();
        List<String> failures = new ArrayList<>();

        for (CurriculumSql.Statement statement : statements) {
            String key = RecordedResults.key(statement.schema(), statement.sql());
            if (recorded.containsKey(key)) {
                continue;
            }
            try {
                QueryResult result = executionService.execute(statement.sql(), statement.schema());
                recorded.put(key, zeroDuration(result));
            } catch (SqlRejectedException e) {
                failures.add("REFUSED  [" + statement.schema() + "] " + oneLine(statement.sql())
                             + System.lineSeparator() + "           " + e.getMessage());
            } catch (SQLException e) {
                failures.add("FAILED   [" + statement.schema() + "] " + oneLine(statement.sql())
                             + System.lineSeparator() + "           " + e.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            log.error("{} of {} statements did not run:{}{}", failures.size(), statements.size(),
                    System.lineSeparator(), String.join(System.lineSeparator(), failures));
            // Nothing is written. A partial file would quietly drop the broken
            // examples and leave the content looking verified.
            throw new IllegalStateException(
                    failures.size() + " curriculum statements did not run; nothing was recorded.");
        }

        Files.createDirectories(output.toAbsolutePath().getParent());
        writer.writeValue(output.toFile(), recorded);
        log.info("Recorded {} distinct statements from {} references to {}",
                recorded.size(), statements.size(), output.toAbsolutePath());
    }

    /**
     * Blanks the measured duration.
     *
     * <p>A {@link QueryResult} is a record, so this rebuilds it rather than
     * mutating. The alternative — leaving the real timing in — makes the
     * committed file churn on every run for information nobody acts on.
     */
    private static QueryResult zeroDuration(QueryResult result) {
        return new QueryResult(result.columns(), result.rows(), result.rowCount(),
                result.truncated(), result.rowLimit(), 0L, result.schema());
    }

    private static String oneLine(String sql) {
        String flat = sql.strip().replaceAll("\\s+", " ");
        return flat.length() <= 100 ? flat : flat.substring(0, 97) + "...";
    }
}

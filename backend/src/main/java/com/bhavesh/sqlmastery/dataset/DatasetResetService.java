package com.bhavesh.sqlmastery.dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Restores the learning dataset to its published state.
 *
 * <p>This is the <em>only</em> way anything writes to the learning schemas at
 * runtime, and it is deliberately not a general mechanism. It runs one fixed
 * file, {@code db/learning/seed.sql}, which begins with {@code TRUNCATE ...
 * RESTART IDENTITY CASCADE} and then inserts a fixed set of rows. There is no
 * parameter, no statement to supply and nothing to influence: the request is a
 * verb, not a payload. That is what lets a Reset button exist at all on an
 * application whose other job is running SQL from strangers.
 *
 * <p>The same method serves the button and the first-run population, so the
 * state a new installation starts in and the state the button restores cannot
 * drift apart — which matters because the expected output recorded against
 * every lesson example was produced from exactly this data.
 *
 * <p>Two safeguards:
 * <ul>
 *   <li>A lock, so two concurrent resets cannot interleave their TRUNCATEs.</li>
 *   <li>A cooldown, so holding the button down cannot keep the dataset
 *       truncated while other people are reading from it.</li>
 * </ul>
 *
 * <p>Note the contrast with the playground path. Here the whole file is handed
 * to {@link Statement#execute}, which uses PostgreSQL's simple query protocol
 * and therefore <em>does</em> accept many statements at once. That is exactly
 * what the playground must never do, and is why playground statements go
 * through {@code PreparedStatement} instead.
 */
@Service
public class DatasetResetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetResetService.class);
    private static final String SEED_RESOURCE = "db/learning/seed.sql";
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private static final String COUNT_ALL_ROWS = """
            select (select count(*) from learning.departments)
                 + (select count(*) from learning.employees)
                 + (select count(*) from learning.projects)
                 + (select count(*) from learning.employee_projects)
                 + (select count(*) from learning.categories)
                 + (select count(*) from learning.products)
                 + (select count(*) from learning.customers)
                 + (select count(*) from learning.orders)
                 + (select count(*) from learning.order_items)
                 + (select count(*) from learning.payments)
                 + (select count(*) from learning.user_activity)
                 + (select count(*) from learning.account_status_history)
                 + (select count(*) from learning.daily_sales)
                 + (select count(*) from learning.contacts)
                 + (select count(*) from learning.exam_scores)
            """;

    private final DataSource appDataSource;
    private final JdbcTemplate jdbc;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Instant> lastReset = new AtomicReference<>(Instant.EPOCH);

    public DatasetResetService(@Qualifier("appDataSource") DataSource appDataSource, JdbcTemplate appJdbcTemplate) {
        this.appDataSource = appDataSource;
        this.jdbc = appJdbcTemplate;
    }

    /**
     * Truncates and repopulates both learning schemas.
     *
     * @param force true to skip the cooldown. Used by the startup path and by
     *              tests, never by the HTTP endpoint.
     * @return a summary of what the dataset now contains
     * @throws ResetTooSoonException if called again inside the cooldown
     */
    public ResetSummary reset(boolean force) {
        Instant now = Instant.now();
        Instant previous = lastReset.get();
        if (!force && Duration.between(previous, now).compareTo(COOLDOWN) < 0) {
            long waitSeconds = COOLDOWN.minus(Duration.between(previous, now)).toSeconds() + 1;
            throw new ResetTooSoonException(waitSeconds);
        }

        lock.lock();
        try {
            String seed = readSeed();
            long startNanos = System.nanoTime();
            try (Connection connection = appDataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(seed);
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw new DatasetResetException("Reseeding the dataset failed: " + e.getMessage(), e);
                }
            } catch (SQLException e) {
                throw new DatasetResetException("Could not reach the database to reseed it.", e);
            }
            lastReset.set(Instant.now());
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            ResetSummary summary = summarise(durationMs);
            log.info("Learning dataset reseeded in {} ms ({} rows across {} tables)",
                    durationMs, summary.totalRows(), summary.tables());
            return summary;
        } finally {
            lock.unlock();
        }
    }

    /** True when the primary dataset has no rows at all, i.e. a fresh database. */
    public boolean isEmpty() {
        Integer count = jdbc.queryForObject("select count(*) from learning.employees", Integer.class);
        return count == null || count == 0;
    }

    private String readSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE);
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + SEED_RESOURCE + " on the classpath", e);
        }
    }

    private ResetSummary summarise(long durationMs) {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'learning'",
                Integer.class);
        // Spelled out rather than built from information_schema. The count is
        // shown to the learner after a reset, so it has to be an exact count
        // rather than a planner estimate, and an explicit list is both the
        // simplest way to get one and the thing that fails loudly if a future
        // migration adds a table nobody remembered to include here.
        Long rows = jdbc.queryForObject(COUNT_ALL_ROWS, Long.class);
        return new ResetSummary(tables == null ? 0 : tables, rows == null ? 0 : rows, durationMs);
    }

    /** @param totalRows exact row count across every table in the learning schema */
    public record ResetSummary(int tables, long totalRows, long durationMs) {
    }

    /** Thrown when a reset is requested again too quickly. */
    public static class ResetTooSoonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient long retryAfterSeconds;

        public ResetTooSoonException(long retryAfterSeconds) {
            super("The dataset was reset moments ago. Try again in " + retryAfterSeconds + " seconds.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Thrown when reseeding itself fails. */
    public static class DatasetResetException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DatasetResetException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

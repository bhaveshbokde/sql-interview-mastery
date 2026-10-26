package com.bhavesh.sqlmastery.playground;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import com.bhavesh.sqlmastery.playground.dto.ColumnMeta;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Executes a validated statement against the learning dataset.
 *
 * <p>Every query runs under all of these at once:
 *
 * <ul>
 *   <li>a connection belonging to the least-privileged learner role;</li>
 *   <li>an explicit transaction that is read-only and is <em>always</em>
 *       rolled back, successful or not, so nothing a query does can outlive
 *       it;</li>
 *   <li>{@code SET LOCAL statement_timeout}, which PostgreSQL enforces and
 *       which disappears with the transaction;</li>
 *   <li>{@code Statement.setMaxRows}, so the driver stops materialising rows
 *       rather than the backend running out of heap;</li>
 *   <li>a process-wide permit count, so a burst is refused rather than queued
 *       into connection starvation.</li>
 * </ul>
 *
 * <p>The statement is sent through {@link PreparedStatement}, which puts it on
 * the extended query protocol. That is not a stylistic choice: PostgreSQL does
 * not accept multiple statements in one extended-protocol message, so
 * {@code select 1; drop table t} is refused by the server regardless of what
 * the validator did or did not notice.
 *
 * <p>The statement text itself is never concatenated from user input anywhere —
 * there is no user input to concatenate, because the statement <em>is</em> the
 * input. The one value this class does interpolate is the schema name, and it
 * is checked against the configured allow-list by equality first; see
 * {@link #resolveSchema}.
 */
@Service
public class QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionService.class);

    /** Longer than statement_timeout, so PostgreSQL cancels first and reports 57014. */
    private static final int JDBC_TIMEOUT_MARGIN_MS = 2_000;

    private final DataSource learnerDataSource;
    private final SqlSafetyValidator validator;
    private final PlaygroundProperties properties;
    private final Semaphore permits;

    public QueryExecutionService(
            @Qualifier("learnerDataSource") DataSource learnerDataSource,
            SqlSafetyValidator validator,
            PlaygroundProperties properties) {
        this.learnerDataSource = learnerDataSource;
        this.validator = validator;
        this.properties = properties;
        this.permits = new Semaphore(properties.maxConcurrent(), true);
    }

    /**
     * Validates and runs a statement.
     *
     * @throws SqlRejectedException if the statement must not run
     * @throws PlaygroundBusyException if the process is already running as many
     *         queries as it allows
     * @throws SQLException if PostgreSQL rejects or fails the query. The caller
     *         turns this into a {@code QueryError}; it is not swallowed here,
     *         because the SQLSTATE is the most useful thing about it.
     */
    public QueryResult execute(String rawSql, String requestedSchema) throws SQLException {
        String sql = validator.validate(rawSql);
        String schema = resolveSchema(requestedSchema);

        boolean acquired;
        try {
            acquired = permits.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaygroundBusyException("The request was interrupted before it could run.");
        }
        if (!acquired) {
            throw new PlaygroundBusyException(
                    "The playground is already running " + properties.maxConcurrent()
                    + " queries. Try again in a moment.");
        }
        try {
            return runInReadOnlyTransaction(sql, schema);
        } finally {
            permits.release();
        }
    }

    private QueryResult runInReadOnlyTransaction(String sql, String schema) throws SQLException {
        long startNanos = System.nanoTime();
        try (Connection connection = learnerDataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try {
                applySessionLimits(connection, schema);
                return readResult(connection, sql, schema, startNanos);
            } finally {
                // Always roll back. A SELECT has nothing to commit, so this
                // costs nothing; what it buys is the certainty that no path
                // through this method can leave work behind. The connection
                // goes back to the pool with no open transaction, so the next
                // borrower cannot inherit a SET LOCAL from this one either.
                safeRollback(connection);
            }
        }
    }

    private void applySessionLimits(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // SET LOCAL, not SET: these last exactly as long as the transaction,
            // and the rollback above removes them.
            statement.execute("set local statement_timeout = " + properties.statementTimeoutMs());
            statement.execute("set local search_path = " + schema);
        }
    }

    private QueryResult readResult(Connection connection, String sql, String schema, long startNanos)
            throws SQLException {
        // Escaped here rather than in the validator, so that both the
        // validator and any error message the learner sees work on the text
        // exactly as it was typed. See JdbcPlaceholders for why this is
        // necessary at all.
        try (PreparedStatement statement = connection.prepareStatement(JdbcPlaceholders.escape(sql))) {
            // One row more than the limit, so "there were more" can be
            // distinguished from "there were exactly this many". Truncating
            // silently is how someone concludes their GROUP BY is wrong.
            statement.setMaxRows(properties.maxRows() + 1);
            statement.setFetchSize(Math.min(properties.maxRows() + 1, 1_000));
            statement.setQueryTimeout(
                    (int) Duration.ofMillis(properties.statementTimeoutMs() + JDBC_TIMEOUT_MARGIN_MS)
                            .toSeconds() + 1);

            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<ColumnMeta> columns = describeColumns(meta);

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == properties.maxRows()) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        row.add(toJsonValue(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (log.isDebugEnabled()) {
                    log.debug("playground query ok: schema={} rows={} truncated={} durationMs={}",
                            schema, rows.size(), truncated, durationMs);
                }
                return new QueryResult(columns, rows, rows.size(), truncated,
                        properties.maxRows(), durationMs, schema);
            }
        }
    }

    private List<ColumnMeta> describeColumns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(new ColumnMeta(
                    meta.getColumnLabel(i),
                    meta.getColumnTypeName(i),
                    alignmentFor(meta.getColumnType(i))));
        }
        return columns;
    }

    private static String alignmentFor(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> "numeric";
            default -> "text";
        };
    }

    /**
     * Converts a JDBC value into something Jackson can serialise without
     * guessing.
     *
     * <p>Numbers stay numbers so the grid can right-align them and so a test
     * comparing results does not have to care about formatting. Dates and
     * timestamps become ISO-8601 strings rather than epoch millis, because the
     * whole point of the date lessons is that a date is not a number. A
     * {@code jsonb} column arrives as a driver-specific object and is passed
     * through as its text, which is already JSON.
     */
    static Object toJsonValue(Object value) throws SQLException {
        return switch (value) {
            case null -> null;
            case Boolean b -> b;
            case BigDecimal d -> d;
            case Number n -> n;
            case java.sql.Timestamp ts -> ts.toLocalDateTime().toString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Time t -> t.toLocalTime().toString();
            case byte[] bytes -> "\\x" + HexFormat.of().formatHex(bytes);
            case Array array -> arrayToList(array);
            case String s -> s;
            default -> value.toString();
        };
    }

    private static List<Object> arrayToList(Array array) throws SQLException {
        Object raw = array.getArray();
        List<Object> out = new ArrayList<>();
        if (raw instanceof Object[] values) {
            for (Object element : values) {
                out.add(toJsonValue(element));
            }
        } else {
            out.add(String.valueOf(raw));
        }
        array.free();
        return out;
    }

    /**
     * Maps a requested schema name onto an allowed one.
     *
     * <p>The returned value is interpolated into {@code SET LOCAL search_path},
     * which is one of the few places in this application where a string ends up
     * in SQL text — {@code search_path} cannot be set from a bind parameter.
     * So the value returned here is never the caller's string: it is the
     * matching entry from the configured allow-list, found by comparison. An
     * unrecognised name produces an error, not an escaped version of itself.
     */
    private String resolveSchema(String requested) {
        if (requested == null || requested.isBlank()) {
            return "learning";
        }
        String normalised = requested.trim().toLowerCase(Locale.ROOT);
        for (String allowed : properties.allowedSchemas()) {
            if (allowed.equals(normalised)) {
                return allowed;
            }
        }
        throw new SqlRejectedException(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED,
                "There is no dataset called \"" + requested + "\". Available datasets: "
                + properties.allowedSchemas().stream().sorted().toList() + ".");
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            // The connection is being discarded either way. Log it rather than
            // let it mask the original failure, which is what rethrowing from a
            // finally block would do.
            log.warn("Rolling back a playground transaction failed: {}", e.getMessage());
        }
    }

    /** Thrown when the process is already at its concurrent-query limit. */
    public static class PlaygroundBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PlaygroundBusyException(String message) {
            super(message);
        }
    }
}

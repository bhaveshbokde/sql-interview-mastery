package com.bhavesh.sqlmastery.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits applied to every query typed into the playground.
 *
 * <p>All of these are enforced on the server. The frontend displays them so a
 * learner knows why a query was cut off, but it never decides them: a request
 * from curl gets exactly the same treatment as one from the UI.
 *
 * @param statementTimeoutMs how long a single query may run before PostgreSQL
 *                           cancels it. Enforced by {@code statement_timeout}
 *                           on the connection, so the cancellation happens in
 *                           the database rather than by abandoning a thread.
 * @param maxRows            the most rows returned to the caller. Applied with
 *                           {@code Statement.setMaxRows}, which stops the JDBC
 *                           driver materialising more than that, so a
 *                           {@code SELECT} over a large join cannot exhaust the
 *                           heap.
 * @param maxSqlLength       the longest statement accepted, in characters.
 *                           Checked before parsing, because the parser is
 *                           itself an attack surface.
 * @param allowedSchemas     the only schemas a query may name. A reference to
 *                           anything else — including {@code app}, where the
 *                           solutions live, and {@code pg_catalog} — is
 *                           rejected during validation.
 * @param maxConcurrent      how many playground queries may run at once across
 *                           the whole process. Beyond this, requests are
 *                           refused rather than queued, so a burst cannot tie
 *                           up every connection in the learner pool.
 * @param ratePerMinute      per-client query budget, refilled continuously.
 */
@ConfigurationProperties(prefix = "sqlmastery.playground")
public record PlaygroundProperties(
        int statementTimeoutMs,
        int maxRows,
        int maxSqlLength,
        Set<String> allowedSchemas,
        int maxConcurrent,
        int ratePerMinute) {

    public PlaygroundProperties {
        if (statementTimeoutMs <= 0) {
            throw new IllegalArgumentException("statementTimeoutMs must be positive");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        if (maxSqlLength <= 0) {
            throw new IllegalArgumentException("maxSqlLength must be positive");
        }
        if (allowedSchemas == null || allowedSchemas.isEmpty()) {
            throw new IllegalArgumentException("at least one allowed schema is required");
        }
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("ratePerMinute must be positive");
        }
        allowedSchemas = Set.copyOf(allowedSchemas);
    }
}

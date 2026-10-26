package com.bhavesh.sqlmastery.playground.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The outcome of a successful query.
 *
 * @param columns     one entry per result column, in order.
 * @param rows        row-major values. A null in the data is a JSON null, which
 *                    the grid renders as a dimmed NULL rather than an empty
 *                    cell — telling an empty string from a NULL by looking is
 *                    most of what the three-valued-logic lesson is about.
 * @param rowCount    how many rows are in {@code rows}.
 * @param truncated   true when the query produced more rows than the limit and
 *                    the rest were discarded. Reported rather than hidden: a
 *                    silently-truncated result is how someone concludes their
 *                    aggregate is wrong.
 * @param rowLimit    the limit that was applied.
 * @param durationMs  wall-clock time for the execute and fetch, measured in the
 *                    backend. It includes network time to PostgreSQL, so it is
 *                    an upper bound on query time, not a benchmark.
 * @param schema      the dataset the query ran against.
 */
@Schema(description = "A successful query result.")
public record QueryResult(
        List<ColumnMeta> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        int rowLimit,
        long durationMs,
        String schema) {
}

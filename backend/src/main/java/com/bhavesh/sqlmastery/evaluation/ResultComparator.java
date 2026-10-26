package com.bhavesh.sqlmastery.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bhavesh.sqlmastery.playground.dto.ColumnMeta;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import org.springframework.stereotype.Component;

/**
 * Compares a submitted query's result against the reference solution's.
 *
 * <p><b>What this does and does not prove.</b> It compares two <em>result
 * sets</em> produced by running two queries over one dataset. It does not and
 * cannot decide whether two arbitrary SQL queries are equivalent — that
 * problem is undecidable in general, and undecidable in practice long before
 * that. A submission that agrees with the reference answer here has agreed on
 * this data; it has not been proved correct. The mitigation is to run the same
 * comparison against a second dataset ({@code learning_alt}) built out of the
 * cases that separate a right answer from a lucky one — ties, NULLs, an empty
 * table, a group with fewer members than the question asks for. Two datasets
 * is better than one and is still not a proof, and the UI says so rather than
 * reporting "Correct" as though it were a theorem.
 *
 * <p><b>Row order.</b> Compared as a multiset unless the problem declares
 * itself order-sensitive. A query without {@code ORDER BY} has no defined row
 * order, so failing a correct answer because PostgreSQL returned the rows in a
 * different sequence would be teaching something false. Where the question does
 * ask for an order, the order is checked exactly.
 *
 * <p><b>Values.</b> Numbers compare numerically, so {@code 150} and
 * {@code 150.00} match — they are the same number, and which one comes back
 * depends on whether an expression went through {@code numeric} or
 * {@code integer}, which is not what the question was about. Everything else
 * compares as text, exactly, including case and trailing spaces.
 *
 * <p><b>Column names.</b> Compared case-insensitively against the names the
 * problem asks for, because SQL identifiers are case-insensitive unless
 * quoted. Column <em>order</em> is significant: a question that asks for
 * {@code (department, employee, salary)} is asking for them in that order.
 */
@Component
public class ResultComparator {

    /**
     * Characters used to build the comparison key for a row.
     *
     * <p>A row is reduced to one string so that counting duplicates is a map
     * lookup. Joining the values needs a separator that cannot occur in the
     * data, and tagging each value with its kind stops a boolean false and the
     * text "false", or the number 1 and the text "1", from colliding — which
     * would quietly accept a wrong answer. ASCII control characters are used
     * because PostgreSQL text columns in this dataset never contain them.
     */
    private static final char FIELD_SEPARATOR = '\u001f';
    private static final String TAG_NULL = "\u0000NULL";
    private static final String TAG_BOOLEAN = "\u0001";
    private static final String TAG_NUMBER = "\u0002";
    private static final String TAG_LIST = "\u0003";

    /**
     * @param problemColumns the column names the problem requires, in order
     * @param orderSensitive whether row order is part of the answer
     */
    public Comparison compare(
            QueryResult submitted,
            QueryResult reference,
            List<String> problemColumns,
            boolean orderSensitive) {

        List<String> notes = new ArrayList<>();

        List<String> actual = submitted.columns().stream().map(ColumnMeta::name).toList();
        if (!columnsMatch(actual, problemColumns)) {
            notes.add("The result has columns " + actual + " but the question asks for "
                      + problemColumns + ". Name them with AS, in the order the question lists them.");
            return new Comparison(false, "COLUMNS", notes, null, 0, 0);
        }

        if (submitted.truncated() || reference.truncated()) {
            notes.add("The result was truncated at the row limit, so it cannot be compared in full. "
                      + "Narrow the query.");
            return new Comparison(false, "TRUNCATED", notes, null, submitted.rowCount(), reference.rowCount());
        }

        int submittedRows = submitted.rowCount();
        int referenceRows = reference.rowCount();

        if (orderSensitive) {
            return compareInOrder(submitted, reference, notes);
        }
        return compareAsMultiset(submitted, reference, notes, submittedRows, referenceRows);
    }

    private Comparison compareInOrder(QueryResult submitted, QueryResult reference, List<String> notes) {
        List<List<Object>> a = submitted.rows();
        List<List<Object>> b = reference.rows();
        int limit = Math.min(a.size(), b.size());
        for (int i = 0; i < limit; i++) {
            if (!rowsEqual(a.get(i), b.get(i))) {
                notes.add("Row " + (i + 1) + " differs. Expected " + format(b.get(i))
                          + " but got " + format(a.get(i))
                          + ". This question specifies an order, so ORDER BY matters.");
                return new Comparison(false, "ROW_MISMATCH", notes, i + 1, a.size(), b.size());
            }
        }
        if (a.size() != b.size()) {
            notes.add(describeCount(a.size(), b.size()));
            return new Comparison(false, "ROW_COUNT", notes, limit + 1, a.size(), b.size());
        }
        return new Comparison(true, "MATCH", notes, null, a.size(), b.size());
    }

    private Comparison compareAsMultiset(
            QueryResult submitted,
            QueryResult reference,
            List<String> notes,
            int submittedRows,
            int referenceRows) {

        Map<String, Integer> expected = tally(reference.rows());
        Map<String, Integer> got = tally(submitted.rows());

        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        expected.forEach((row, count) -> {
            int have = got.getOrDefault(row, 0);
            if (have < count) {
                missing.add(row + (count - have > 1 ? " (x" + (count - have) + ")" : ""));
            }
        });
        got.forEach((row, count) -> {
            int want = expected.getOrDefault(row, 0);
            if (count > want) {
                extra.add(row + (count - want > 1 ? " (x" + (count - want) + ")" : ""));
            }
        });

        if (missing.isEmpty() && extra.isEmpty()) {
            return new Comparison(true, "MATCH", notes, null, submittedRows, referenceRows);
        }
        if (!missing.isEmpty()) {
            notes.add("Missing " + missing.size() + " row(s), for example " + first(missing) + ".");
        }
        if (!extra.isEmpty()) {
            notes.add("Returned " + extra.size() + " row(s) that should not be there, for example "
                      + first(extra) + ".");
        }
        if (!missing.isEmpty() && !extra.isEmpty() && missing.size() == extra.size()) {
            notes.add("The same number of rows is missing as is extra, which usually means a value "
                      + "is slightly off — a rounding difference, or an aggregate over the wrong "
                      + "set of rows — rather than the wrong rows being selected.");
        } else if (extra.isEmpty() && submittedRows < referenceRows) {
            notes.add("Rows are being dropped. An INNER JOIN where a LEFT JOIN is needed, or a "
                      + "condition on the right-hand table in WHERE rather than in ON, both do this.");
        } else if (missing.isEmpty() && submittedRows > referenceRows) {
            notes.add("Extra rows usually mean a join is multiplying rows. Check whether the join "
                      + "key is unique on both sides.");
        }
        return new Comparison(false, "ROW_MISMATCH", notes, null, submittedRows, referenceRows);
    }

    private static boolean columnsMatch(List<String> actual, List<String> required) {
        if (actual.size() != required.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).equalsIgnoreCase(required.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Integer> tally(List<List<Object>> rows) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<Object> row : rows) {
            counts.merge(key(row), 1, Integer::sum);
        }
        return counts;
    }

    private String key(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Object value : row) {
            sb.append(normalise(value)).append(FIELD_SEPARATOR);
        }
        return sb.toString();
    }

    private static boolean rowsEqual(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!normalise(a.get(i)).equals(normalise(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reduces a value to the form used for comparison.
     *
     * <p>Numbers go through {@link BigDecimal#stripTrailingZeros} so that
     * {@code 150}, {@code 150.0} and {@code 150.00} are one value. They are the
     * same number; which representation comes back depends on the types the
     * expression passed through, and failing an answer over that would teach
     * nothing except to distrust the grader.
     *
     * <p>A boolean is normalised to {@code true}/{@code false} so that a
     * {@code boolean} column and a {@code text} column containing "true" do not
     * compare equal by accident.
     */
    private static String normalise(Object value) {
        return switch (value) {
            case null -> TAG_NULL;
            case Boolean b -> TAG_BOOLEAN + b;
            case Number n -> {
                BigDecimal decimal = n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
                yield TAG_NUMBER + decimal.stripTrailingZeros().toPlainString();
            }
            case String s -> numericText(s);
            case List<?> list -> {
                StringBuilder sb = new StringBuilder(TAG_LIST + "[");
                list.forEach(element -> sb.append(normalise(element)).append(','));
                yield sb.append(']').toString();
            }
            default -> value.toString();
        };
    }

    /**
     * Treats a string that is unambiguously a number as a number.
     *
     * <p>PostgreSQL returns {@code numeric} as a {@code BigDecimal} but returns
     * the same value as text if the query cast it, and a learner who writes
     * {@code to_char(salary, 'FM999999')} has not given a wrong answer to a
     * question about which employees earn the most.
     */
    private static String numericText(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[+-]?\\d+(\\.\\d+)?")) {
            return value;
        }
        return TAG_NUMBER + new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
    }

    private static String describeCount(int actual, int expected) {
        return "Expected " + expected + " row" + (expected == 1 ? "" : "s")
               + " but got " + actual + ".";
    }

    private static String format(List<Object> row) {
        List<String> parts = new ArrayList<>(row.size());
        for (Object value : row) {
            parts.add(value == null ? "NULL" : String.valueOf(value));
        }
        return "(" + String.join(", ", parts) + ")";
    }

    /**
     * Renders one canonical row key for a human.
     *
     * <p>A key is built for comparison, not for reading: fields are joined with
     * a non-printing separator and numbers carry a type tag so that {@code 1}
     * and {@code '1'} do not compare equal. Both of those have to be stripped
     * before the string reaches a message, or the learner is shown control
     * characters and wonders what they mean.
     */
    private static String first(List<String> rows) {
        String readable = rows.get(0)
                .replace(TAG_NULL, "NULL")
                .replace(TAG_BOOLEAN, "")
                .replace(TAG_NUMBER, "")
                .replace(TAG_LIST, "")
                .replace(FIELD_SEPARATOR, '|');
        if (readable.endsWith("|")) {
            readable = readable.substring(0, readable.length() - 1);
        }
        return readable.length() <= 90 ? readable : readable.substring(0, 87) + "...";
    }

    /**
     * The verdict.
     *
     * @param code one of MATCH, COLUMNS, ROW_MISMATCH, ROW_COUNT, TRUNCATED
     * @param firstDifferingRow 1-based, for order-sensitive problems only
     */
    public record Comparison(
            boolean matches,
            String code,
            List<String> notes,
            Integer firstDifferingRow,
            int submittedRowCount,
            int referenceRowCount) {

        public Comparison {
            notes = List.copyOf(notes);
        }
    }

}

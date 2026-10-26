package com.bhavesh.sqlmastery.evaluation;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import com.bhavesh.sqlmastery.playground.dto.ColumnMeta;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answer checking has to be strict enough to catch a wrong query and lenient
 * enough not to fail a right one on formatting. These tests pin down exactly
 * where that line is, because every one of those decisions is arguable and
 * the arguable ones are the ones that get changed by accident.
 */
class ResultComparatorTest {

    private final ResultComparator comparator = new ResultComparator();

    private static QueryResult result(List<String> columns, List<List<Object>> rows) {
        List<ColumnMeta> meta = columns.stream()
                .map(name -> new ColumnMeta(name, "text", "text"))
                .toList();
        return new QueryResult(meta, rows, rows.size(), false, 500, 0L, "learning");
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("identical results match")
        void identicalMatches() {
            QueryResult r = result(List.of("a", "b"), List.of(row(1, "x"), row(2, "y")));
            var c = comparator.compare(r, r, List.of("a", "b"), false);
            assertThat(c.matches()).isTrue();
            assertThat(c.code()).isEqualTo("MATCH");
            assertThat(c.notes()).isEmpty();
        }

        @Test
        @DisplayName("an empty result matches an empty reference")
        void emptyMatchesEmpty() {
            QueryResult empty = result(List.of("a"), List.of());
            var c = comparator.compare(empty, empty, List.of("a"), false);
            assertThat(c.matches()).isTrue();
        }

        @Test
        @DisplayName("row order is ignored when the question did not ask for one")
        void orderInsensitiveByDefault() {
            QueryResult submitted = result(List.of("a"), List.of(row(2), row(1), row(3)));
            QueryResult reference = result(List.of("a"), List.of(row(1), row(2), row(3)));
            assertThat(comparator.compare(submitted, reference, List.of("a"), false).matches())
                    .isTrue();
        }

        @Test
        @DisplayName("row order matters when the problem says it does")
        void orderSensitiveWhenAsked() {
            QueryResult submitted = result(List.of("a"), List.of(row(2), row(1)));
            QueryResult reference = result(List.of("a"), List.of(row(1), row(2)));
            var c = comparator.compare(submitted, reference, List.of("a"), true);
            assertThat(c.matches()).isFalse();
            assertThat(c.firstDifferingRow()).isEqualTo(1);
        }

        @Test
        @DisplayName("duplicate rows are counted, not deduplicated")
        void comparesAsMultisets() {
            QueryResult submitted = result(List.of("a"), List.of(row(1), row(1), row(2)));
            QueryResult reference = result(List.of("a"), List.of(row(1), row(2)));
            // A multiset comparison catches a fan-out bug that a set
            // comparison would silently accept.
            assertThat(comparator.compare(submitted, reference, List.of("a"), false).matches())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("value equality")
    class Values {

        @Test
        @DisplayName("numerically equal values of different types match")
        void numericEquality() {
            QueryResult submitted = result(List.of("n"), List.of(row(new BigDecimal("100.00"))));
            QueryResult reference = result(List.of("n"), List.of(row(100)));
            // 100 and 100.00 are the same answer. Failing a correct query
            // because the learner used sum() where the reference used count()
            // would be useless pedantry.
            assertThat(comparator.compare(submitted, reference, List.of("n"), false).matches())
                    .isTrue();
        }

        @Test
        @DisplayName("text that is unambiguously a number is accepted as that number")
        void numericTextIsTreatedAsNumeric() {
            QueryResult submitted = result(List.of("n"), List.of(row("100")));
            QueryResult reference = result(List.of("n"), List.of(row(100)));
            // A deliberate leniency, and the most arguable decision in the
            // comparator. A learner who writes to_char(salary, 'FM999999')
            // has not given a wrong answer to "who earns the most", so the
            // text form is accepted. The cost is that it will not catch a
            // genuinely missing cast, which is a real trade rather than an
            // oversight — and is pinned here so that changing it is a
            // decision rather than an accident.
            assertThat(comparator.compare(submitted, reference, List.of("n"), false).matches())
                    .isTrue();
        }

        @Test
        @DisplayName("text that is not a number stays text")
        void nonNumericTextIsNotCoerced() {
            QueryResult submitted = result(List.of("n"), List.of(row("100 rupees")));
            QueryResult reference = result(List.of("n"), List.of(row(100)));
            assertThat(comparator.compare(submitted, reference, List.of("n"), false).matches())
                    .isFalse();
        }

        @Test
        @DisplayName("NULL matches NULL and nothing else")
        void nullHandling() {
            QueryResult withNull = result(List.of("a"), List.of(row((Object) null)));
            QueryResult withEmpty = result(List.of("a"), List.of(row("")));
            QueryResult withZero = result(List.of("a"), List.of(row(0)));

            assertThat(comparator.compare(withNull, withNull, List.of("a"), false).matches()).isTrue();
            assertThat(comparator.compare(withNull, withEmpty, List.of("a"), false).matches()).isFalse();
            assertThat(comparator.compare(withNull, withZero, List.of("a"), false).matches()).isFalse();
        }

        @Test
        @DisplayName("booleans are distinguished from the strings that look like them")
        void booleanHandling() {
            QueryResult asBoolean = result(List.of("b"), List.of(row(true)));
            QueryResult asText = result(List.of("b"), List.of(row("true")));
            assertThat(comparator.compare(asBoolean, asText, List.of("b"), false).matches()).isFalse();
        }
    }

    @Nested
    @DisplayName("columns")
    class Columns {

        @Test
        @DisplayName("the required columns must be present, named and in order")
        void columnsMustMatchTheQuestion() {
            QueryResult submitted = result(List.of("salary", "name"), List.of(row(1, "x")));
            var c = comparator.compare(submitted, submitted, List.of("name", "salary"), false);
            assertThat(c.matches()).isFalse();
            assertThat(c.code()).isEqualTo("COLUMNS");
            assertThat(c.notes()).isNotEmpty();
        }

        @Test
        @DisplayName("the column complaint names both lists, so the fix is obvious")
        void columnMessageIsActionable() {
            QueryResult submitted = result(List.of("a"), List.of(row(1)));
            var c = comparator.compare(submitted, submitted, List.of("a", "b"), false);
            assertThat(c.notes().getFirst())
                    .contains("[a]")
                    .contains("[a, b]");
        }

        @Test
        @DisplayName("column checking happens before row checking")
        void columnsCheckedFirst() {
            // "your column is called salary, the question asked for
            // SecondHighest" is a better first message than a row mismatch
            // that was caused by it.
            QueryResult submitted = result(List.of("wrong"), List.of(row(1)));
            QueryResult reference = result(List.of("right"), List.of(row(2)));
            assertThat(comparator.compare(submitted, reference, List.of("right"), false).code())
                    .isEqualTo("COLUMNS");
        }
    }

    @Nested
    @DisplayName("the failure message")
    class Messages {

        @Test
        @DisplayName("reports how many rows are missing and how many are extra")
        void describesTheDifference() {
            QueryResult submitted = result(List.of("a"), List.of(row(1), row(99)));
            QueryResult reference = result(List.of("a"), List.of(row(1), row(2)));
            var c = comparator.compare(submitted, reference, List.of("a"), false);
            assertThat(c.matches()).isFalse();
            assertThat(c.notes()).anyMatch(n -> n.contains("Missing"));
            assertThat(c.notes()).anyMatch(n -> n.contains("should not be there"));
        }

        @Test
        @DisplayName("contains no control characters from the comparison keys")
        void messageIsReadable() {
            QueryResult submitted = result(List.of("a", "b"), List.of(row(1, "x")));
            QueryResult reference = result(List.of("a", "b"), List.of(row(2, "y")));
            var c = comparator.compare(submitted, reference, List.of("a", "b"), false);
            // The canonical row key is built with ASCII control characters so
            // that 1 and '1' cannot collide. None of them should survive into
            // something a learner reads.
            assertThat(c.notes()).isNotEmpty();
            for (String note : c.notes()) {
                assertThat(note.chars().filter(ch -> ch < 0x20 && ch != '\n').count())
                        .describedAs("control characters in: %s", note)
                        .isZero();
            }
        }

        @Test
        @DisplayName("reports a row-count difference distinctly")
        void reportsRowCounts() {
            QueryResult submitted = result(List.of("a"), List.of(row(1)));
            QueryResult reference = result(List.of("a"), List.of(row(1), row(2), row(3)));
            var c = comparator.compare(submitted, reference, List.of("a"), false);
            assertThat(c.submittedRowCount()).isEqualTo(1);
            assertThat(c.referenceRowCount()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a truncated result cannot be judged correct")
    void truncatedIsNotAMatch() {
        List<List<Object>> rows = List.of(row(1));
        QueryResult truncated = new QueryResult(
                List.of(new ColumnMeta("a", "int4", "numeric")), rows, 1, true, 1, 0L, "learning");
        QueryResult reference = result(List.of("a"), rows);
        var c = comparator.compare(truncated, reference, List.of("a"), false);
        // The rows that were returned match, and the ones that were cut off
        // are unknown. Calling that correct would be asserting something the
        // comparison cannot see.
        assertThat(c.matches()).isFalse();
        assertThat(c.code()).isEqualTo("TRUNCATED");
    }
}

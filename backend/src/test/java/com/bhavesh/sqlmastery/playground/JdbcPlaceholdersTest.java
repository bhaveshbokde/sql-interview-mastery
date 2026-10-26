package com.bhavesh.sqlmastery.playground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The escape exists so PostgreSQL's {@code ?} operators survive JDBC.
 *
 * <p>The two properties that matter are opposites, and both are easy to break:
 * a {@code ?} that is part of the SQL must be doubled, and a {@code ?} inside
 * a literal must not be. The second is what turns a correctness fix into a
 * correctness bug if it is written as a blanket {@code replace}.
 */
class JdbcPlaceholdersTest {

    @Test
    @DisplayName("leaves a statement with no question mark completely alone")
    void passesThroughUntouched() {
        String sql = "select * from employees where dept_id = 10";
        assertThat(JdbcPlaceholders.escape(sql)).isSameAs(sql);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = ';', value = {
        "select 1 where a ? 'k'     ; select 1 where a ?? 'k'",
        "select a ? 'x' and b ? 'y' ; select a ?? 'x' and b ?? 'y'",
    })
    @DisplayName("doubles a question mark that is part of the SQL")
    void escapesOperators(String input, String expected) {
        assertThat(JdbcPlaceholders.escape(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("doubles the leading mark of the ?| and ?& operators")
    void escapesMultiCharacterOperators() {
        // Written as explicit assertions rather than CSV rows because the
        // operator itself contains the delimiter characters a parameterised
        // source would need.
        assertThat(JdbcPlaceholders.escape("select 1 where a ?| array['k']"))
                .isEqualTo("select 1 where a ??| array['k']");
        assertThat(JdbcPlaceholders.escape("select 1 where a ?& array['k']"))
                .isEqualTo("select 1 where a ??& array['k']");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "select 'what?' as q",
        "select 'a?b?c' from employees",
        "select e.email from employees e where e.email like '%?%'",
        "select '' || '?' as q",
    })
    @DisplayName("leaves a question mark inside a string literal alone")
    void doesNotEscapeInsideStringLiterals(String sql) {
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("handles a doubled quote inside a string literal")
    void handlesEscapedQuotes() {
        // 'O''Connor?' is one literal containing an apostrophe and a question
        // mark. A scanner that treated the doubled quote as a close would
        // think the ? was outside the literal and escape it.
        String sql = "select 'O''Connor?' as name";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("leaves a question mark inside a quoted identifier alone")
    void doesNotEscapeInsideQuotedIdentifiers() {
        String sql = "select 1 as \"why?\"";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("leaves a question mark inside a dollar-quoted block alone")
    void doesNotEscapeInsideDollarQuotes() {
        assertThat(JdbcPlaceholders.escape("select $$what?$$ as q"))
                .isEqualTo("select $$what?$$ as q");
        assertThat(JdbcPlaceholders.escape("select $tag$what?$tag$ as q"))
                .isEqualTo("select $tag$what?$tag$ as q");
    }

    @Test
    @DisplayName("leaves a question mark inside a line comment alone")
    void doesNotEscapeInsideLineComments() {
        String sql = "select 1 -- why? because\nfrom employees";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("leaves a question mark inside a block comment alone")
    void doesNotEscapeInsideBlockComments() {
        String sql = "select 1 /* why? */ from employees";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("handles nested block comments, which PostgreSQL allows")
    void handlesNestedBlockComments() {
        String sql = "select 1 /* outer /* inner? */ still outer? */ from employees";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }

    @Test
    @DisplayName("escapes outside a literal and not inside, in the same statement")
    void mixedContexts() {
        String sql = "select 'a?b' from products where attributes ? 'gpu'";
        assertThat(JdbcPlaceholders.escape(sql))
                .isEqualTo("select 'a?b' from products where attributes ?? 'gpu'");
    }

    @Test
    @DisplayName("a lone hyphen or slash is not the start of a comment")
    void doesNotMistakeOperatorsForComments() {
        assertThat(JdbcPlaceholders.escape("select 1 - 2, 6 / 3, a ? 'k'"))
                .isEqualTo("select 1 - 2, 6 / 3, a ?? 'k'");
    }

    @Test
    @DisplayName("an unterminated literal is copied rather than rewritten")
    void toleratesUnterminatedLiteral() {
        // The validator has already parsed and accepted the statement, so
        // this should be unreachable. If it ever is reached, copying the
        // remainder unchanged is the behaviour least likely to make things
        // worse.
        String sql = "select 'unterminated?";
        assertThat(JdbcPlaceholders.escape(sql)).isEqualTo(sql);
    }
}

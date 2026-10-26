package com.bhavesh.sqlmastery.playground;

/**
 * Protects PostgreSQL's {@code ?} operators from the JDBC driver.
 *
 * <p>JDBC reserves {@code ?} as the bind-parameter placeholder. PostgreSQL
 * independently uses {@code ?} as the jsonb key-existence operator, with
 * {@code ?|} and {@code ?&} for "any of" and "all of", and the same character
 * appears in several geometric operators. So a statement that is perfectly
 * valid PostgreSQL —
 *
 * <pre>select * from products where attributes ? 'gpu'</pre>
 *
 * <p>— reaches the driver as a statement with one unbound parameter and fails
 * with {@code No value specified for parameter 1}. The error names a parameter
 * the learner never wrote, which makes it one of the least guessable failures
 * in the whole system.
 *
 * <p>The PostgreSQL JDBC driver's documented escape for a literal question mark
 * is to double it: {@code ??} is sent to the server as {@code ?}. This class
 * applies that escape to every {@code ?} that is actually part of the SQL,
 * which is every one of them — the playground never binds parameters, because
 * the statement <em>is</em> the input and there is nothing to parameterise.
 *
 * <p>What it must not do is rewrite a question mark inside a literal. A
 * {@code ?} in a string constant, a dollar-quoted block, a quoted identifier or
 * a comment is data, and doubling it would change the result. So this is a
 * scanner rather than a {@code replace} call: it tracks which of those five
 * contexts it is in and only escapes in ordinary code.
 *
 * <p>Escaping is deliberately applied <em>after</em> validation and
 * <em>before</em> execution, so the validator and any error message the learner
 * sees both work on the text as written.
 */
final class JdbcPlaceholders {

    private JdbcPlaceholders() {
    }

    /**
     * Doubles every {@code ?} that is part of the statement's syntax.
     *
     * @param sql a validated statement, as the learner wrote it
     * @return the same statement with {@code ?} escaped for the JDBC driver
     */
    static String escape(String sql) {
        if (sql.indexOf('?') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + 8);
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            switch (c) {
                case '\'' -> i = copySingleQuoted(sql, i, out);
                case '"' -> i = copyDoubleQuoted(sql, i, out);
                case '$' -> i = copyDollarQuoted(sql, i, out);
                case '-' -> i = copyLineCommentOrChar(sql, i, out);
                case '/' -> i = copyBlockCommentOrChar(sql, i, out);
                case '?' -> {
                    out.append("??");
                    i++;
                }
                default -> {
                    out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }

    /** Copies a {@code '...'} literal, in which {@code ''} is an escaped quote. */
    private static int copySingleQuoted(String sql, int start, StringBuilder out) {
        out.append('\'');
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c);
            i++;
            if (c == '\'') {
                if (i < sql.length() && sql.charAt(i) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                return i;
            }
        }
        return i;
    }

    /** Copies a {@code "..."} identifier, in which {@code ""} is an escaped quote. */
    private static int copyDoubleQuoted(String sql, int start, StringBuilder out) {
        out.append('"');
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            out.append(c);
            i++;
            if (c == '"') {
                if (i < sql.length() && sql.charAt(i) == '"') {
                    out.append('"');
                    i++;
                    continue;
                }
                return i;
            }
        }
        return i;
    }

    /**
     * Copies a dollar-quoted block such as {@code $$...$$} or {@code $tag$...$tag$}.
     *
     * <p>A {@code $} that does not open a valid tag — {@code $1}, or a lone
     * dollar — is copied as an ordinary character.
     */
    private static int copyDollarQuoted(String sql, int start, StringBuilder out) {
        int close = sql.indexOf('$', start + 1);
        if (close < 0) {
            out.append('$');
            return start + 1;
        }
        String tag = sql.substring(start, close + 1);
        if (!isDollarTag(tag)) {
            out.append('$');
            return start + 1;
        }
        int end = sql.indexOf(tag, close + 1);
        if (end < 0) {
            // Unterminated. Copy the rest verbatim; the parser already
            // accepted this text, so refusing here would be worse than
            // leaving it alone.
            out.append(sql, start, sql.length());
            return sql.length();
        }
        out.append(sql, start, end + tag.length());
        return end + tag.length();
    }

    /** {@code $$} or {@code $identifier$} — anything else is not a dollar quote. */
    private static boolean isDollarTag(String tag) {
        for (int i = 1; i < tag.length() - 1; i++) {
            char c = tag.charAt(i);
            if (!Character.isLetter(c) && c != '_' && !(i > 1 && Character.isDigit(c))) {
                return false;
            }
        }
        return true;
    }

    /** Copies a {@code -- ...} comment to the end of the line, or a lone hyphen. */
    private static int copyLineCommentOrChar(String sql, int start, StringBuilder out) {
        if (start + 1 < sql.length() && sql.charAt(start + 1) == '-') {
            int newline = sql.indexOf('\n', start);
            int end = newline < 0 ? sql.length() : newline;
            out.append(sql, start, end);
            return end;
        }
        out.append('-');
        return start + 1;
    }

    /**
     * Copies a {@code /* ... *}{@code /} comment, or a lone slash.
     *
     * <p>PostgreSQL block comments nest, so this counts depth rather than
     * stopping at the first close.
     */
    private static int copyBlockCommentOrChar(String sql, int start, StringBuilder out) {
        if (start + 1 >= sql.length() || sql.charAt(start + 1) != '*') {
            out.append('/');
            return start + 1;
        }
        int depth = 0;
        int i = start;
        while (i < sql.length()) {
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                out.append("/*");
                i += 2;
            } else if (i + 1 < sql.length() && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                out.append("*/");
                i += 2;
                if (depth == 0) {
                    return i;
                }
            } else {
                out.append(sql.charAt(i));
                i++;
            }
        }
        return i;
    }
}

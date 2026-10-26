package com.bhavesh.sqlmastery.playground;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Turns a PostgreSQL SQLSTATE into a sentence that teaches something.
 *
 * <p>PostgreSQL's error messages are unusually good, but they describe the
 * failure rather than the misunderstanding that caused it. {@code column
 * "dept_name" must appear in the GROUP BY clause} is accurate and complete, and
 * still leaves a beginner with no idea what to do; what they need to hear is
 * that every select-list column has to be either grouped or aggregated,
 * because the group is one row and the column has many values.
 *
 * <p>The explanations are deliberately keyed on SQLSTATE rather than on message
 * text. Message wording changes between PostgreSQL releases and is localised;
 * SQLSTATE is part of the documented interface. Anything not listed here gets
 * no explanation at all rather than a guess.
 */
@Component
public class SqlErrorExplainer {

    private static final Map<String, String> BY_SQLSTATE = Map.ofEntries(
            Map.entry("42703", """
                    That column does not exist on any table in the FROM clause. Three things \
                    cause this more often than a typo: the column belongs to a table you have \
                    not joined yet; you are using an alias defined in the SELECT list inside \
                    WHERE, which is not allowed because WHERE runs before SELECT; or the column \
                    was created with double quotes and now has to be quoted every time."""),

            Map.entry("42P01", """
                    No such table or view. Unqualified names in the playground resolve against \
                    the learning schema, so check the spelling against the schema panel on the \
                    left. If you meant a CTE, make sure it is defined in the same statement — a \
                    WITH clause does not survive to the next query."""),

            Map.entry("42803", """
                    A column in the SELECT list is neither grouped nor aggregated. Each group \
                    collapses to one row, so PostgreSQL has no way to choose which of the \
                    group's values to show. Either add the column to GROUP BY, or wrap it in an \
                    aggregate such as MIN, MAX or STRING_AGG — and if you reach for MIN only to \
                    silence the error, that is a sign the grouping is not the one you wanted."""),

            Map.entry("42P10", """
                    An invalid column reference, usually in ORDER BY or GROUP BY. Positional \
                    references such as ORDER BY 2 count select-list items, so they break when \
                    the list changes; and in a UNION, ORDER BY applies to the whole result and \
                    can only name output columns, not columns of one branch."""),

            Map.entry("42601", """
                    A syntax error. Work outwards from the reported position: the usual causes \
                    are a missing comma between select-list items, an extra comma before FROM, \
                    an unbalanced bracket, or a reserved word used as an identifier without \
                    quotes."""),

            Map.entry("42883", """
                    No function exists with that name and those argument types. PostgreSQL \
                    resolves functions by argument type, so this often means a cast is missing \
                    rather than that the function is absent — for instance passing text where \
                    the function expects a date."""),

            Map.entry("42804", """
                    Type mismatch. PostgreSQL is much stricter than MySQL here and will not \
                    silently coerce text to a number. Cast explicitly with ::numeric or \
                    CAST(x AS numeric), and remember that a comparison between an integer \
                    column and a quoted '5' is only accepted when the literal is unambiguous."""),

            Map.entry("22012", """
                    Division by zero. In SQL this raises rather than producing infinity or null, \
                    so guard the denominator: NULLIF(denominator, 0) turns the zero into NULL \
                    and the whole expression into NULL, which is usually what a report wants."""),

            Map.entry("22P02", """
                    A value could not be parsed as the target type — most often a string that \
                    does not look like a number or a date. Check the literal, and check that you \
                    are not comparing a text column to a number and relying on an implicit \
                    cast."""),

            Map.entry("22003", """
                    A numeric value is out of range for its type. numeric(10,2) holds at most \
                    eight digits before the decimal point, so a SUM over many rows can overflow \
                    a column type that was fine for a single row."""),

            Map.entry("21000", """
                    A scalar subquery returned more than one row. A subquery used where a single \
                    value is expected — in a SELECT list, or on one side of = — must return at \
                    most one row. Either add a filter that makes it unique, or switch the \
                    comparison to IN or EXISTS."""),

            Map.entry("25006", """
                    The statement tried to write inside a read-only transaction. The playground \
                    runs every query as a role whose transactions are read-only, which is why \
                    the dataset survives other people experimenting with it."""),

            Map.entry("42501", """
                    Permission denied. The playground role can SELECT from the learning schemas \
                    and nothing else — it cannot read the application's own tables, which is \
                    where the solutions are kept."""),

            Map.entry("57014", """
                    The query ran past the statement timeout and PostgreSQL cancelled it. That \
                    is almost never a slow machine: look for a join with no join condition, \
                    which multiplies row counts, or a correlated subquery being re-run for every \
                    row. EXPLAIN will show which."""),

            Map.entry("53300", """
                    Too many connections. The playground deliberately keeps a very small \
                    connection pool so that one person cannot exhaust the database; try \
                    again in a moment."""),

            Map.entry("54001", """
                    The statement is too complex for the parser — usually extreme nesting. \
                    Break it into CTEs, which also makes it readable."""),

            Map.entry("0A000", """
                    PostgreSQL recognises the syntax but does not implement it here. This is the \
                    error you get for things that are legal in other dialects, such as MySQL's \
                    LIMIT 1,2 or a window function in the WHERE clause — the latter is not a \
                    PostgreSQL limitation but a logical one, since window functions are \
                    evaluated after WHERE."""));

    /**
     * @return an explanation for this SQLSTATE, or null when there is nothing
     *         useful to say. Null is a deliberate outcome, not a gap to fill
     *         with a generic sentence.
     */
    public String explain(String sqlState) {
        return sqlState == null ? null : BY_SQLSTATE.get(sqlState);
    }
}

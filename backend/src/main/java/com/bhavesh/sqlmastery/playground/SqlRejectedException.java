package com.bhavesh.sqlmastery.playground;

/**
 * Thrown when a statement is refused before it ever reaches the database.
 *
 * <p>Carries two messages on purpose. {@code reason} is the short rule that was
 * broken, suitable for a test assertion and for the {@code code} field of the
 * API response. {@code getMessage()} is the sentence shown to the learner, and
 * is written to teach rather than to scold: "INSERT is not allowed in the
 * playground" is useless without "the dataset is shared, so the editor is
 * read-only; use Reset Dataset if you want a clean slate".
 */
public class SqlRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Reason reason;

    public enum Reason {
        EMPTY,
        TOO_LONG,
        UNPARSEABLE,
        MULTIPLE_STATEMENTS,
        NOT_A_SELECT,
        DATA_MODIFYING_CTE,
        SELECT_INTO,
        LOCKING_CLAUSE,
        SCHEMA_NOT_ALLOWED,
        FUNCTION_NOT_ALLOWED,
        TOO_MANY_NAME_PARTS
    }

    public SqlRejectedException(Reason reason, String learnerMessage) {
        super(learnerMessage);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

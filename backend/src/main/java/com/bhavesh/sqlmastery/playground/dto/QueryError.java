package com.bhavesh.sqlmastery.playground.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A failed query, described in a way a learner can act on.
 *
 * @param code        a stable identifier: either a {@code SqlRejectedException}
 *                    reason such as {@code NOT_A_SELECT}, or the PostgreSQL
 *                    SQLSTATE such as {@code 42703}. Tests assert on this;
 *                    humans read {@code message}.
 * @param message     what PostgreSQL said, or what the validator refused.
 * @param explanation what the error usually means and what to try, written for
 *                    someone learning rather than someone debugging. Null when
 *                    the error is not one of the recognised kinds — an
 *                    unhelpful guess would be worse than nothing.
 * @param position    1-based character offset into the submitted statement
 *                    where PostgreSQL stopped, when it reports one. The editor
 *                    uses it to put the caret on the problem.
 * @param hint        PostgreSQL's own HINT, when there is one. Frequently the
 *                    single most useful line, and frequently never seen because
 *                    tools discard it.
 */
@Schema(description = "A rejected or failed query.")
public record QueryError(
        String code,
        String message,
        String explanation,
        Integer position,
        String hint) {
}

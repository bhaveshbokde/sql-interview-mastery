package com.bhavesh.sqlmastery.playground.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A statement submitted to the playground.
 *
 * @param sql    the statement. Length is bounded here as well as in the
 *               validator so an oversized body is refused by Bean Validation
 *               before any parsing happens.
 * @param schema which dataset to run against. Null means {@code learning}.
 *               {@code learning_alt} holds the same tables with rows chosen to
 *               break naive answers, and the problem pages offer it as a
 *               "check your query against different data" button.
 */
@Schema(description = "A read-only SQL statement to execute against the learning dataset.")
public record QueryRequest(

        @NotBlank(message = "sql must not be blank")
        @Size(max = 10_000, message = "sql must be at most 10000 characters")
        @Schema(example = "select dept_id, count(*) from employees group by dept_id order by 2 desc")
        String sql,

        @Size(max = 64)
        @Schema(description = "learning (default) or learning_alt", example = "learning")
        String schema) {
}

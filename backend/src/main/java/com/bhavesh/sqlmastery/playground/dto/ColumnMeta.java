package com.bhavesh.sqlmastery.playground.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One column of a result set.
 *
 * @param name      the label the query produced. Unlabelled expressions come
 *                  back from PostgreSQL as {@code ?column?}, which the UI shows
 *                  verbatim rather than inventing a name — noticing it is how a
 *                  learner discovers that aliases are not decoration.
 * @param typeName  the PostgreSQL type, such as {@code numeric} or {@code int4}.
 * @param alignment {@code numeric} or {@code text}, so the grid can right-align
 *                  numbers without the frontend having to know the PostgreSQL
 *                  type catalogue.
 */
@Schema(description = "Metadata for one result column.")
public record ColumnMeta(String name, String typeName, String alignment) {
}

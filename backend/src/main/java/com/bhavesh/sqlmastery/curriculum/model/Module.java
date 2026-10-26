package com.bhavesh.sqlmastery.curriculum.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A group of lessons, as authored in {@code resources/curriculum/modules.yaml}.
 *
 * @param position the order modules are meant to be worked through. It is a
 *                 dependency order rather than a difficulty order: the
 *                 window-function module is harder than the schema-design one
 *                 but comes first, because the interview problems lean on
 *                 window functions and not on normal forms
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Module(
        String id,
        int position,
        String title,
        String slug,
        String summary,
        String icon) {
}

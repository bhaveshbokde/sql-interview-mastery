package com.bhavesh.sqlmastery.curriculum;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Finds every executable statement inside a piece of serialised content.
 *
 * <p>Three things need to agree on what counts as a statement: the recorder
 * that executes them, the injector that attaches their results, and the test
 * that re-checks them. If any two disagreed, the symptom would be a statement
 * that is recorded but never displayed, or displayed but never checked — and
 * the second of those is exactly the defect this whole arrangement exists to
 * prevent. So all three call this.
 *
 * <p>The rule: any object with a non-blank {@code sql} or {@code solutionSql}
 * is a statement, running against the schema named by a sibling {@code schema}
 * field, or {@code learning} when there is none.
 */
public final class CurriculumSql {

    private CurriculumSql() {
    }

    /** One statement and the dataset it runs against. */
    public record Statement(String schema, String sql, ObjectNode owner, String field) {
    }

    public static List<Statement> statements(JsonNode content) {
        List<Statement> found = new ArrayList<>();
        walk(content, found);
        return found;
    }

    private static void walk(JsonNode node, List<Statement> found) {
        if (node.isArray()) {
            node.forEach(child -> walk(child, found));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        String schema = object.hasNonNull("schema") ? object.get("schema").asText() : "learning";
        for (String field : new String[] {"sql", "solutionSql"}) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                found.add(new Statement(schema, value.asText(), object, field));
            }
        }
        object.properties().forEach(entry -> walk(entry.getValue(), found));
    }
}

package com.bhavesh.sqlmastery.curriculum;

import java.util.ArrayList;
import java.util.List;

import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Attaches recorded query output to every statement in a piece of content.
 *
 * <p>Statements are found by {@link CurriculumSql}, which the recorder also
 * uses, so what gets displayed and what got executed are the same set by
 * construction rather than by two implementations happening to agree.
 *
 * <p>A statement with no recorded output is collected and reported rather than
 * skipped. Rendering an example with an empty result table would be a lie told
 * quietly; refusing to start is a lie refused loudly.
 */
@Component
public class ResultInjector {

    private final RecordedResults recorded;
    private final ObjectMapper objectMapper;

    public ResultInjector(RecordedResults recorded, ObjectMapper objectMapper) {
        this.recorded = recorded;
        this.objectMapper = objectMapper;
    }

    /**
     * @param content mutated in place
     * @param describe how to label a missing statement in the report
     * @return the statements with no recorded output
     */
    public List<String> inject(JsonNode content, String describe) {
        List<String> missing = new ArrayList<>();
        for (CurriculumSql.Statement statement : CurriculumSql.statements(content)) {
            QueryResult result = recorded.find(statement.schema(), statement.sql());
            if (result == null) {
                missing.add(describe + ": no recorded output for [" + statement.schema() + "] "
                            + summarise(statement.sql()));
                continue;
            }
            // Which field receives the output is decided by the record that
            // declared it. Content is serialised with nulls kept precisely so
            // that this is readable from the tree: a Problem declares
            // "expected", everything else declares "result".
            ObjectNode owner = statement.owner();
            owner.set(owner.has("expected") ? "expected" : "result", objectMapper.valueToTree(result));
        }
        return missing;
    }

    private static String summarise(String sql) {
        String oneLine = sql.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 70 ? oneLine : oneLine.substring(0, 67) + "...";
    }
}

package com.bhavesh.sqlmastery.curriculum;

import java.util.List;

import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three things must agree on what counts as an executable statement: the
 * recorder that runs them, the injector that attaches their results, and the
 * loader that refuses content without one. They agree by all calling
 * {@link CurriculumSql}, so this is where the definition is pinned.
 */
class CurriculumSqlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("finds a sql field")
    void findsSql() {
        var found = CurriculumSql.statements(parse("""
                {"title": "x", "sql": "select 1"}
                """));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().sql()).isEqualTo("select 1");
        assertThat(found.getFirst().field()).isEqualTo("sql");
    }

    @Test
    @DisplayName("finds a solutionSql field")
    void findsSolutionSql() {
        var found = CurriculumSql.statements(parse("""
                {"solutionSql": "select 2"}
                """));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().field()).isEqualTo("solutionSql");
    }

    @Test
    @DisplayName("defaults to the learning schema")
    void defaultsToLearning() {
        var found = CurriculumSql.statements(parse("""
                {"sql": "select 1"}
                """));
        assertThat(found.getFirst().schema()).isEqualTo("learning");
    }

    @Test
    @DisplayName("uses a sibling schema field when there is one")
    void honoursSchemaField() {
        var found = CurriculumSql.statements(parse("""
                {"sql": "select 1", "schema": "learning_alt"}
                """));
        assertThat(found.getFirst().schema()).isEqualTo("learning_alt");
    }

    @Test
    @DisplayName("schema must be a sibling of sql — it is not inherited by nested objects")
    void schemaIsNotInherited() {
        // Worth pinning because the opposite is the intuitive assumption. A
        // `schema` on a parent node does not reach a `sql` nested inside it;
        // the two have to be on the same object. In the curriculum that is
        // exactly how extraTests are written — name, schema, note and sql all
        // on one entry — and an extra test that put the schema a level up
        // would silently run against `learning` and record the wrong answer.
        var found = CurriculumSql.statements(parse("""
                {"schema": "learning_alt", "child": {"sql": "select 1"}}
                """));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().schema()).isEqualTo("learning");

        var sibling = CurriculumSql.statements(parse("""
                {"schema": "learning_alt", "sql": "select 1"}
                """));
        assertThat(sibling.getFirst().schema()).isEqualTo("learning_alt");
    }

    @Test
    @DisplayName("walks arrays and nested objects")
    void walksEverything() {
        var found = CurriculumSql.statements(parse("""
                {
                  "examples":  [{"sql": "select 1"}, {"sql": "select 2"}],
                  "exercises": [{"solutionSql": "select 3"}],
                  "deep": {"deeper": {"sql": "select 4"}}
                }
                """));
        assertThat(found).hasSize(4);
    }

    @Test
    @DisplayName("ignores a blank or absent sql")
    void ignoresBlank() {
        assertThat(CurriculumSql.statements(parse("""
                {"sql": "", "other": "   ", "note": "not sql"}
                """))).isEmpty();
        assertThat(CurriculumSql.statements(parse("""
                {"sql": null}
                """))).isEmpty();
    }

    @Test
    @DisplayName("does not treat a lineByLine clause as a statement")
    void ignoresExplanationClauses() {
        // The field is called `clause` precisely so that a fragment such as
        // "where rnk = 2" is not sent to the recorder to be executed. If this
        // ever fails, the model has drifted back to calling it `sql`.
        var found = CurriculumSql.statements(parse("""
                {"lineByLine": [{"clause": "where rnk = 2", "note": "..."}],
                 "solutionSql": "select 1"}
                """));
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().sql()).isEqualTo("select 1");
    }

    @Test
    @DisplayName("an object with both sql and solutionSql yields two statements")
    void findsBothFieldsOnOneObject() {
        var found = CurriculumSql.statements(parse("""
                {"sql": "select 1", "solutionSql": "select 2"}
                """));
        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("the owner node is the object the statement was found on")
    void reportsTheOwner() {
        ObjectNode root = parse("""
                {"examples": [{"sql": "select 1", "title": "t"}]}
                """);
        var found = CurriculumSql.statements(root);
        assertThat(found.getFirst().owner().get("title").asText()).isEqualTo("t");
    }

    @Test
    @DisplayName("the recorded-result key normalises whitespace and nothing else")
    void keyNormalisation() {
        // Reindenting a query in the YAML must not invalidate its recorded
        // output; changing a column must.
        assertThat(RecordedResults.key("learning", "select  1\n  from t"))
                .isEqualTo(RecordedResults.key("learning", "select 1 from t"));
        assertThat(RecordedResults.key("learning", "select a from t"))
                .isNotEqualTo(RecordedResults.key("learning", "select b from t"));
        assertThat(RecordedResults.key("learning", "select 1"))
                .isNotEqualTo(RecordedResults.key("learning_alt", "select 1"));
    }

    @Test
    @DisplayName("a null or blank schema keys the same as learning")
    void keyDefaultsSchema() {
        String expected = RecordedResults.key("learning", "select 1");
        assertThat(RecordedResults.key(null, "select 1")).isEqualTo(expected);
        assertThat(RecordedResults.key("  ", "select 1")).isEqualTo(expected);
    }

    @Test
    @DisplayName("the injector attaches a recorded result and reports a missing one")
    void injectorBehaviour() {
        QueryResult recorded = new QueryResult(List.of(), List.of(), 0, false, 500, 0L, "learning");
        var results = new RecordedResults(java.util.Map.of(
                RecordedResults.key("learning", "select 1"), recorded));
        var injector = new ResultInjector(results, mapper);

        ObjectNode present = parse("""
                {"sql": "select 1", "result": null}
                """);
        assertThat(injector.inject(present, "test")).isEmpty();
        assertThat(present.get("result").isNull()).isFalse();

        ObjectNode absent = parse("""
                {"sql": "select 999", "result": null}
                """);
        var missing = injector.inject(absent, "lesson x");
        assertThat(missing).hasSize(1);
        assertThat(missing.getFirst()).contains("lesson x").contains("select 999");
    }

    @Test
    @DisplayName("the injector writes to `expected` when the node declares one")
    void injectorPrefersExpected() {
        QueryResult recorded = new QueryResult(List.of(), List.of(), 0, false, 500, 0L, "learning");
        var results = new RecordedResults(java.util.Map.of(
                RecordedResults.key("learning", "select 1"), recorded));
        var injector = new ResultInjector(results, mapper);

        // A Problem declares `expected`; everything else declares `result`.
        // Which field receives the output is read from the tree rather than
        // hard-coded, which is why content is serialised with nulls kept.
        ObjectNode problem = parse("""
                {"solutionSql": "select 1", "expected": null}
                """);
        assertThat(injector.inject(problem, "problem p")).isEmpty();
        assertThat(problem.get("expected").isNull()).isFalse();
        assertThat(problem.has("result")).isFalse();
    }
}

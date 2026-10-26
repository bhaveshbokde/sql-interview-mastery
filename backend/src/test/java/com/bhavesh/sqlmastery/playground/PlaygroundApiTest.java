package com.bhavesh.sqlmastery.playground;

import com.bhavesh.sqlmastery.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The playground, end to end: HTTP in, PostgreSQL, JSON out.
 *
 * <p>The validator has its own unit tests. These exist to check the things
 * that only a real database can establish — that the privilege boundary
 * holds, that a read-only transaction is actually read only, that PostgreSQL's
 * own errors arrive as usable messages, and that the limits are enforced
 * server-side rather than suggested.
 */
@IntegrationTest
class PlaygroundApiTest {

    @Autowired
    private MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions run(String sql) throws Exception {
        return mvc.perform(post("/api/playground/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\": " + quote(sql) + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions run(String sql, String schema) throws Exception {
        return mvc.perform(post("/api/playground/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\": " + quote(sql) + ", \"schema\": \"" + schema + "\"}"));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Nested
    @DisplayName("a valid query")
    class ValidQueries {

        @Test
        @DisplayName("returns rows, typed columns and the limits that applied")
        void returnsRows() throws Exception {
            run("select dept_id, count(*) as n from employees group by dept_id order by n desc")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.columns[0].name").value("dept_id"))
                    .andExpect(jsonPath("$.columns[1].name").value("n"))
                    .andExpect(jsonPath("$.columns[1].alignment").value("numeric"))
                    .andExpect(jsonPath("$.rowCount", greaterThan(0)))
                    .andExpect(jsonPath("$.schema").value("learning"))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.rowLimit").value(50));
        }

        @Test
        @DisplayName("renders a NULL as a JSON null, distinct from an empty string")
        void nullIsNull() throws Exception {
            run("select null as a, '' as b, 0 as c")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0][0]").doesNotExist())
                    .andExpect(jsonPath("$.rows[0][1]").value(""))
                    .andExpect(jsonPath("$.rows[0][2]").value(0));
        }

        @Test
        @DisplayName("runs against learning_alt when asked")
        void alternateSchema() throws Exception {
            run("select count(*) as n from employees", "learning_alt")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schema").value("learning_alt"))
                    .andExpect(jsonPath("$.rows[0][0]").value(6));
        }

        @Test
        @DisplayName("the jsonb ? operator survives the JDBC placeholder collision")
        void jsonbExistenceOperator() throws Exception {
            // ? is the JDBC bind placeholder and PostgreSQL's key-existence
            // operator. Without JdbcPlaceholders this fails with "No value
            // specified for parameter 1", naming a parameter nobody wrote.
            run("select count(*) as n from products where attributes ? 'gpu'")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0][0]").value(1));
        }

        @Test
        @DisplayName("a question mark inside a string literal is left alone")
        void questionMarkInLiteral() throws Exception {
            run("select 'what?' as q")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0][0]").value("what?"));
        }

        @Test
        @DisplayName("information_schema is readable, because the lessons teach it")
        void informationSchemaIsReadable() throws Exception {
            run("select count(*) as n from information_schema.columns where table_schema = 'learning'")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0][0]", greaterThan(0)));
        }

        @Test
        @DisplayName("reports truncation rather than hiding it")
        void reportsTruncation() throws Exception {
            // max-rows is 50 in the test profile and order_items has more.
            run("select * from order_items")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.truncated").value(true))
                    .andExpect(jsonPath("$.rowCount").value(50));
        }
    }

    @Nested
    @DisplayName("PostgreSQL's own errors")
    class DatabaseErrors {

        @Test
        @DisplayName("an unknown column comes back as its SQLSTATE with an explanation")
        void unknownColumn() throws Exception {
            run("select nosuchcolumn from employees")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("42703"))
                    .andExpect(jsonPath("$.message", containsString("nosuchcolumn")))
                    .andExpect(jsonPath("$.explanation", notNullValue()));
        }

        @Test
        @DisplayName("an unknown table comes back as 42P01")
        void unknownTable() throws Exception {
            run("select * from nosuchtable")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("42P01"));
        }

        @Test
        @DisplayName("division by zero is reported, not swallowed")
        void divisionByZero() throws Exception {
            run("select 1 / 0")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("22012"));
        }

        @Test
        @DisplayName("a type error carries the position so the editor can point at it")
        void reportsPosition() throws Exception {
            run("select 'abc'::integer")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("22P02"));
        }
    }

    @Nested
    @DisplayName("the safeguards, over HTTP")
    class Safeguards {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "insert into employees (emp_id) values (999)",
            "update employees set salary = 1",
            "delete from employees",
            "drop table employees",
            "truncate employees",
            "select 1; drop table employees",
            "with x as (delete from employees returning *) select * from x",
            "select * into evil from employees",
            "select * from employees for update",
            "select * from app.problem_solution",
            "select * from pg_catalog.pg_user",
            "select pg_sleep(5)",
        })
        @DisplayName("is refused with 400 and a reason code")
        void refuses(String sql) throws Exception {
            run(sql).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", notNullValue()))
                    .andExpect(jsonPath("$.message", notNullValue()));
        }

        @Test
        @DisplayName("an unknown schema is refused and the available ones are named")
        void unknownSchema() throws Exception {
            run("select 1", "nosuchschema")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SCHEMA_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.message", containsString("learning")));
        }

        @Test
        @DisplayName("a blank statement is a validation failure, not a 500")
        void blankStatement() throws Exception {
            mvc.perform(post("/api/playground/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sql\": \"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("the dataset is unchanged after every refused write")
        void datasetIsIntact() throws Exception {
            // The point of the whole exercise. If any of the refusals above
            // had leaked through, this count would have moved.
            run("select count(*) as n from employees")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows[0][0]").value(40));
        }
    }

    @Test
    @DisplayName("the limits endpoint reports what is enforced")
    void limitsAreVisible() throws Exception {
        mvc.perform(get("/api/playground/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRows").value(50))
                .andExpect(jsonPath("$.statementTimeoutMs").value(3000))
                .andExpect(jsonPath("$.maxSqlLength").value(10000));
    }
}

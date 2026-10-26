package com.bhavesh.sqlmastery.playground;

import java.util.Set;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The validator's job is to refuse anything that is not a single read-only
 * SELECT, and to say why in a way a learner can act on.
 *
 * <p>These tests assert on the {@link SqlRejectedException.Reason}, not on the
 * message. The reason is the contract the API exposes as an error code; the
 * message is prose and should be free to improve without breaking a test.
 *
 * <p>No database is involved. The validator is pure, which is the whole reason
 * it is a separate class from the thing that executes statements.
 */
class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = new SqlSafetyValidator(new PlaygroundProperties(
            3_000, 500, 10_000,
            Set.of("learning", "learning_alt", "information_schema"),
            8, 120));

    private SqlRejectedException.Reason rejectionFor(String sql) {
        try {
            validator.validate(sql);
        } catch (SqlRejectedException e) {
            return e.reason();
        }
        throw new AssertionError("Expected [" + sql + "] to be rejected, but it was accepted");
    }

    @Nested
    @DisplayName("accepts the statements the playground exists to run")
    class Accepts {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "select 1",
            "select * from employees",
            "select e.emp_id from learning.employees e",
            "select count(*) from employees group by dept_id having count(*) > 1",
            "with t as (select 1 as n) select n from t",
            "with recursive t as (select 1 as n union all select n + 1 from t where n < 5) select * from t",
            "select 1 union all select 2",
            "select 1 intersect select 1",
            "select 1 except select 2",
            "select * from (values (1), (2)) as v(n)",
            "select rank() over (order by salary) from employees",
            "select count(*) filter (where is_active) from employees",
            "select * from employees order by salary desc limit 10 offset 5",
            "select * from information_schema.columns where table_schema = 'learning'",
            "select e.* from employees e join departments d on d.dept_id = e.dept_id",
            "select * from departments d cross join lateral "
                + "(select 1 from employees e where e.dept_id = d.dept_id limit 1) t",
        })
        void accepts(String sql) {
            assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("strips a trailing semicolon, which is idiomatic to type")
        void stripsTrailingSemicolon() {
            assertThat(validator.validate("select 1;")).isEqualTo("select 1");
            assertThat(validator.validate("select 1;  ")).isEqualTo("select 1");
            assertThat(validator.validate("select 1 ;;")).isEqualTo("select 1");
        }

        @Test
        @DisplayName("trims surrounding whitespace rather than failing on it")
        void trimsWhitespace() {
            assertThat(validator.validate("\n  select 1  \n")).isEqualTo("select 1");
        }
    }

    @Nested
    @DisplayName("refuses anything that writes")
    class RefusesWrites {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "insert into employees (emp_id) values (1)",
            "update employees set salary = 1",
            "delete from employees",
            "drop table employees",
            "truncate employees",
            "create table evil (a int)",
            "alter table employees add column x int",
        })
        void refusesNonSelect(String sql) {
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.NOT_A_SELECT);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "create index on employees (salary)",
            "grant all on employees to public",
            "vacuum employees",
            "explain select * from employees",
        })
        @DisplayName("refuses statements JSqlParser does not model, as unparseable")
        void refusesUnmodelledStatements(String sql) {
            // These are refused with UNPARSEABLE rather than NOT_A_SELECT,
            // because JSqlParser's grammar does not cover them — PostgreSQL's
            // index syntax without a name, the GRANT family, maintenance
            // commands and EXPLAIN. The reason code differs and the outcome
            // does not, which is the property that matters. Asserting it here
            // rather than lumping them in above documents the real behaviour
            // instead of an aspirational one.
            assertThat(rejectionFor(sql)).isIn(
                    SqlRejectedException.Reason.UNPARSEABLE,
                    SqlRejectedException.Reason.NOT_A_SELECT);
        }

        @Test
        @DisplayName("refuses a data-modifying CTE, whose first keyword is WITH")
        void refusesDataModifyingCte() {
            // The single most important case in this class. Every check that
            // looks only at the leading keyword waves this through, and it
            // deletes the table.
            assertThat(rejectionFor("with x as (delete from employees returning *) select * from x"))
                    .isEqualTo(SqlRejectedException.Reason.DATA_MODIFYING_CTE);
            assertThat(rejectionFor(
                    "with x as (insert into employees (emp_id) values (1) returning *) select * from x"))
                    .isEqualTo(SqlRejectedException.Reason.DATA_MODIFYING_CTE);
            assertThat(rejectionFor(
                    "with x as (update employees set salary = 1 returning *) select * from x"))
                    .isEqualTo(SqlRejectedException.Reason.DATA_MODIFYING_CTE);
        }

        @Test
        @DisplayName("refuses SELECT ... INTO, which creates a table")
        void refusesSelectInto() {
            assertThat(rejectionFor("select * into evil from employees"))
                    .isEqualTo(SqlRejectedException.Reason.SELECT_INTO);
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "select * from employees for update",
            "select * from employees for share",
            "select * from employees for no key update",
        })
        void refusesLockingClauses(String sql) {
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.LOCKING_CLAUSE);
        }
    }

    @Nested
    @DisplayName("refuses more than one statement")
    class RefusesMultipleStatements {

        @Test
        void refusesTwoStatements() {
            assertThat(rejectionFor("select 1; select 2"))
                    .isEqualTo(SqlRejectedException.Reason.MULTIPLE_STATEMENTS);
        }

        @Test
        @DisplayName("refuses a SELECT followed by a DROP, the canonical injection payload")
        void refusesSelectThenDrop() {
            assertThat(rejectionFor("select 1; drop table employees"))
                    .isEqualTo(SqlRejectedException.Reason.MULTIPLE_STATEMENTS);
        }
    }

    @Nested
    @DisplayName("confines queries to the learning schemas")
    class ConfinesSchemas {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "select * from app.problem_solution",
            "select * from app.lesson",
            "select * from pg_catalog.pg_user",
            "select * from public.flyway_schema_history",
            "select * from APP.problem_solution",
            "select * from \"app\".problem_solution",
        })
        void refusesOtherSchemas(String sql) {
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED);
        }

        @Test
        @DisplayName("refuses a three-part name, which could reach another database")
        void refusesThreePartNames() {
            assertThat(rejectionFor("select * from sqlmastery_db.app.problem"))
                    .isEqualTo(SqlRejectedException.Reason.TOO_MANY_NAME_PARTS);
        }

        @Test
        @DisplayName("finds a forbidden schema inside a subquery, not just in the outer FROM")
        void refusesForbiddenSchemaInSubquery() {
            assertThat(rejectionFor(
                    "select * from employees where emp_id in (select 1 from app.problem_solution)"))
                    .isEqualTo(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED);
        }

        @Test
        @DisplayName("finds a forbidden schema inside a CTE")
        void refusesForbiddenSchemaInCte() {
            assertThat(rejectionFor("with x as (select * from app.lesson) select * from x"))
                    .isEqualTo(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED);
        }
    }

    @Nested
    @DisplayName("refuses functions that reach outside the database or waste it")
    class RefusesFunctions {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
            "select pg_sleep(10)",
            "select pg_read_file('/etc/passwd')",
            "select lo_import('/etc/passwd')",
            "select dblink('', '')",
            "select pg_terminate_backend(1)",
            "select set_config('x', 'y', false)",
        })
        void refusesDeniedFunctions(String sql) {
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.FUNCTION_NOT_ALLOWED);
        }

        @Test
        @DisplayName("a qualified name is not a way around the deny-list")
        void refusesQualifiedDeniedFunction() {
            // pg_catalog.pg_sleep trips the schema check first, which is also
            // a refusal — what must not happen is acceptance.
            assertThatThrownBy(() -> validator.validate("select pg_catalog.pg_sleep(10)"))
                    .isInstanceOf(SqlRejectedException.class);
        }

        @Test
        @DisplayName("finds a denied function nested inside an expression")
        void refusesNestedDeniedFunction() {
            assertThat(rejectionFor("select coalesce(1, length(pg_read_file('/etc/passwd')))"))
                    .isEqualTo(SqlRejectedException.Reason.FUNCTION_NOT_ALLOWED);
        }

        @Test
        @DisplayName("ordinary functions are fine")
        void allowsOrdinaryFunctions() {
            assertThatCode(() -> validator.validate(
                    "select upper(first_name), length(email), now() from employees"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("refuses malformed or oversized input")
    class RefusesBadInput {

        @Test
        void refusesNull() {
            assertThat(rejectionFor(null)).isEqualTo(SqlRejectedException.Reason.EMPTY);
        }

        @ParameterizedTest(name = "[{index}] blank: [{0}]")
        @ValueSource(strings = {"", "   ", "\n\t "})
        void refusesBlank(String sql) {
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.EMPTY);
        }

        @Test
        void refusesUnparseable() {
            assertThat(rejectionFor("select * form employees"))
                    .isEqualTo(SqlRejectedException.Reason.UNPARSEABLE);
        }

        @Test
        @DisplayName("refuses a statement longer than the configured limit")
        void refusesTooLong() {
            String sql = "select '" + "x".repeat(10_001) + "'";
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.TOO_LONG);
        }

        @Test
        @DisplayName("the length check runs before the parser, which is itself attack surface")
        void checksLengthBeforeParsing() {
            // Deeply nested and oversized. If the parser ran first this would
            // be a StackOverflowError rather than a clean refusal.
            String sql = "select " + "(".repeat(6_000) + "1" + ")".repeat(6_000);
            assertThat(rejectionFor(sql)).isEqualTo(SqlRejectedException.Reason.TOO_LONG);
        }
    }

    @Nested
    @DisplayName("the message is written for a learner")
    class Messages {

        @Test
        void explainsWhyWritesAreRefused() {
            assertThatThrownBy(() -> validator.validate("delete from employees"))
                    .isInstanceOf(SqlRejectedException.class)
                    .hasMessageContaining("read-only")
                    .hasMessageContaining("Reset Dataset");
        }

        @Test
        void namesTheSchemasThatAreAvailable() {
            assertThatThrownBy(() -> validator.validate("select * from app.lesson"))
                    .isInstanceOf(SqlRejectedException.class)
                    .hasMessageContaining("learning")
                    .hasMessageContaining("learning_alt");
        }
    }
}

package com.bhavesh.sqlmastery.playground;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Decides whether a statement typed into the playground may be executed.
 *
 * <p><b>This class is not the security boundary.</b> It is the first of four
 * layers, and the weakest one. It is a Java reimplementation of SQL grammar
 * reading a statement that PostgreSQL will parse with a different parser, and
 * anywhere the two disagree, this one is wrong. The layers that actually
 * contain the damage are, in order of how much they are relied upon:
 *
 * <ol>
 *   <li><b>Privileges.</b> The connection belongs to a role whose entire grant
 *       list is {@code USAGE} on two schemas and {@code SELECT} on their
 *       tables. No {@code INSERT}, no {@code CREATE}, no {@code TEMPORARY}, no
 *       access to the {@code app} schema where the solutions live.</li>
 *   <li><b>Read-only transactions.</b> The role carries
 *       {@code default_transaction_read_only = on}, so PostgreSQL refuses a
 *       write even if the role somehow held the privilege for it.</li>
 *   <li><b>The extended query protocol.</b> Statements are sent through
 *       {@code PreparedStatement}, and the PostgreSQL server rejects multiple
 *       statements in one extended-protocol message. That is a server-side
 *       guarantee against {@code ; DROP TABLE} that owes nothing to
 *       this class.</li>
 *   <li><b>This validator.</b> Which exists to give a learner a clear, early,
 *       teachable error — "the playground is read-only, here is why" — instead
 *       of a raw {@code SQLException}, and to shrink the surface the other
 *       three layers have to defend.</li>
 * </ol>
 *
 * <p>The ordering matters because it is the opposite of the intuitive one.
 * Writing a validator first and granting privileges second produces a system
 * where a parser bug is a breach. Here, a parser bug is a bad error message.
 *
 * <p>What it checks, in the AST rather than in the text:
 * <ul>
 *   <li>Exactly one statement.</li>
 *   <li>That statement is a {@code SELECT} — which includes {@code WITH},
 *       {@code UNION}/{@code INTERSECT}/{@code EXCEPT}, {@code VALUES} and
 *       {@code TABLE t}.</li>
 *   <li>No data-modifying CTE ({@code WITH x AS (DELETE ... RETURNING ...)}),
 *       which is a genuinely write-capable statement whose outermost keyword
 *       is {@code WITH}. A prefix check on the first word would wave it
 *       straight through; this is the clearest reason the validator parses
 *       instead of matching strings.</li>
 *   <li>No {@code SELECT ... INTO}, which creates a table.</li>
 *   <li>No {@code FOR UPDATE} / {@code FOR SHARE}: those take row locks, and
 *       a read-only transaction is not a lock-free one.</li>
 *   <li>Every schema-qualified name is in the allow-list, so {@code app.*} and
 *       {@code pg_catalog.*} are out of reach.</li>
 *   <li>No function from the deny-list below.</li>
 * </ul>
 */
@Component
public class SqlSafetyValidator {

    /**
     * Functions refused by name.
     *
     * <p>Almost every one of these already requires a privilege the learner
     * role does not hold, so the deny-list is defence in depth rather than the
     * thing standing between a learner and the filesystem. The exceptions are
     * {@code pg_sleep} and friends, which any role may call and which exist
     * only to waste a connection; those are the entries that earn their place.
     * {@code statement_timeout} would eventually cut them off anyway, but
     * refusing them outright keeps a sleeping query from occupying one of the
     * few learner connections for three seconds at a time.
     */
    private static final Set<String> DENIED_FUNCTIONS = Set.of(
            "pg_sleep", "pg_sleep_for", "pg_sleep_until",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "pg_read_server_files", "pg_write_server_files",
            "lo_import", "lo_export", "lo_get", "lo_put", "lo_from_bytea",
            "dblink", "dblink_exec", "dblink_connect", "dblink_send_query",
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf",
            "pg_rotate_logfile", "pg_logdir_ls",
            "set_config", "pg_advisory_lock", "pg_advisory_xact_lock",
            "pg_try_advisory_lock", "pg_advisory_unlock", "pg_advisory_unlock_all",
            "query_to_xml", "query_to_xml_and_xmlschema", "table_to_xml",
            "pg_create_physical_replication_slot", "pg_create_logical_replication_slot",
            "pg_drop_replication_slot", "pg_promote",
            "pg_import_system_collations", "pg_file_write", "pg_file_unlink",
            "pg_file_rename", "pg_execute_server_program");

    private final PlaygroundProperties properties;

    public SqlSafetyValidator(PlaygroundProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates a statement and returns it with surrounding whitespace and any
     * trailing semicolon removed.
     *
     * <p>The semicolon is stripped because it is idiomatic to type one and
     * harmless to send one, but a trailing {@code ;} inside an extended-protocol
     * message is a parse error on some driver versions. Stripping it is a
     * convenience, not a security measure — a second statement after the
     * semicolon is rejected above, not quietly discarded here.
     *
     * @throws SqlRejectedException if the statement must not run
     */
    public String validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new SqlRejectedException(SqlRejectedException.Reason.EMPTY,
                    "There is no query to run. Type a SELECT statement and try again.");
        }
        if (rawSql.length() > properties.maxSqlLength()) {
            throw new SqlRejectedException(SqlRejectedException.Reason.TOO_LONG,
                    "That statement is " + rawSql.length() + " characters long; the limit is "
                    + properties.maxSqlLength() + ". Long queries are usually a sign that something "
                    + "should be factored into a CTE.");
        }

        String sql = stripTrailingSemicolon(rawSql.strip());
        Statements parsed = parse(sql);

        // Statements is itself a List<Statement>; getStatements() is deprecated.
        List<Statement> statements = parsed;
        if (statements.size() != 1) {
            throw new SqlRejectedException(SqlRejectedException.Reason.MULTIPLE_STATEMENTS,
                    "Only one statement can run at a time; this contains " + statements.size()
                    + ". Remove the extra semicolon and run each statement separately.");
        }

        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            throw new SqlRejectedException(SqlRejectedException.Reason.NOT_A_SELECT,
                    describeNonSelect(statement));
        }

        Inspector inspector = new Inspector();
        inspector.inspect(select);
        checkSchemas(inspector.schemas, inspector.tooManyParts);
        checkFunctions(inspector.functions);
        return sql;
    }

    private Statements parse(String sql) {
        try {
            return CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new SqlRejectedException(SqlRejectedException.Reason.UNPARSEABLE,
                    "That does not parse as SQL. Check for an unbalanced bracket, a missing comma "
                    + "between select-list items, or a keyword used as a column name without quotes.");
        } catch (StackOverflowError e) {
            // A deeply nested expression can exhaust the parser's stack. That is
            // the parser failing, not the statement being valid, so it is a
            // rejection rather than a 500.
            throw new SqlRejectedException(SqlRejectedException.Reason.UNPARSEABLE,
                    "That statement is nested too deeply to parse. Flatten it, or build it up "
                    + "with a few CTEs instead.");
        }
    }

    private String describeNonSelect(Statement statement) {
        String kind = statement.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        return "The playground runs read-only queries only, and this is a " + kind + ". "
               + "The sample data is shared by every lesson, so letting one person change it would "
               + "quietly break the expected output recorded against every example. Use the "
               + "Reset Dataset button if you want to start from a clean copy.";
    }

    private void checkSchemas(Set<String> schemas, Set<String> tooManyParts) {
        if (!tooManyParts.isEmpty()) {
            throw new SqlRejectedException(SqlRejectedException.Reason.TOO_MANY_NAME_PARTS,
                    "Names with more than two parts are not accepted here (" + tooManyParts
                    + "). Use schema.table.");
        }
        for (String schema : schemas) {
            if (!properties.allowedSchemas().contains(schema.toLowerCase(Locale.ROOT))) {
                throw new SqlRejectedException(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED,
                        "The schema \"" + schema + "\" is not available in the playground. "
                        + "The readable schemas are " + sorted(properties.allowedSchemas())
                        + ". Unqualified table names resolve to learning.");
            }
        }
    }

    private void checkFunctions(Set<String> functions) {
        for (String function : functions) {
            if (DENIED_FUNCTIONS.contains(function)) {
                throw new SqlRejectedException(SqlRejectedException.Reason.FUNCTION_NOT_ALLOWED,
                        "The function " + function + "() is not available in the playground. "
                        + "It either reaches outside the database or exists only to consume "
                        + "resources, and neither is something a SQL exercise needs.");
            }
        }
    }

    private static String sorted(Set<String> values) {
        return values.stream().sorted().toList().toString();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql;
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }

    /**
     * Walks the parsed statement once, collecting everything the checks need.
     *
     * <p>It extends {@link TablesNamesFinder} rather than reimplementing a
     * traversal because that class already visits every node type the grammar
     * can produce — including the ones that are easy to forget, such as the
     * expression inside a window frame or the right-hand side of an
     * {@code IN}. Missing a node would mean a reference this validator never
     * sees, which is precisely the failure mode a hand-rolled walker has.
     */
    private static final class Inspector extends TablesNamesFinder<Void> {

        private final Set<String> schemas = new LinkedHashSet<>();
        private final Set<String> functions = new LinkedHashSet<>();
        private final Set<String> tooManyParts = new LinkedHashSet<>();

        void inspect(Select select) {
            rejectDataModifyingCte(select);
            // getTables() rather than accept(): it initialises the finder's
            // internal bookkeeping before traversing. Calling accept() directly
            // skips that and the first Table node dereferences a null list.
            // The returned set is discarded — what this call is for is the
            // side effects of the overridden visit methods below.
            getTables((Statement) select);
        }

        /**
         * Rejects {@code WITH x AS (INSERT/UPDATE/DELETE ...) SELECT ...}.
         *
         * <p>PostgreSQL allows a CTE to modify data, and the resulting
         * statement is a {@code Select} whose first keyword is {@code WITH}.
         * Every check that looks at the statement type alone passes it.
         */
        private void rejectDataModifyingCte(Select select) {
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems == null) {
                return;
            }
            for (WithItem<?> item : withItems) {
                ParenthesedStatement inner = item.getParenthesedStatement();
                if (!(inner instanceof ParenthesedSelect)) {
                    throw new SqlRejectedException(SqlRejectedException.Reason.DATA_MODIFYING_CTE,
                            "The CTE \"" + item.getUnquotedAliasName() + "\" modifies data. "
                            + "PostgreSQL does allow a CTE to INSERT, UPDATE or DELETE, which is "
                            + "exactly why the playground inspects the parsed statement instead of "
                            + "checking that it starts with SELECT.");
                }
            }
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
                throw new SqlRejectedException(SqlRejectedException.Reason.SELECT_INTO,
                        "SELECT ... INTO creates a new table, so it is a write. Use a CTE "
                        + "(WITH t AS (...)) if you want to name an intermediate result.");
            }
            if (plainSelect.getIntoTempTable() != null) {
                throw new SqlRejectedException(SqlRejectedException.Reason.SELECT_INTO,
                        "SELECT ... INTO TEMP creates a table, so it is a write. Use a CTE instead.");
            }
            rejectLocking(plainSelect.getForMode() != null || plainSelect.getForUpdateTable() != null);
            rejectDataModifyingCte(plainSelect);
            return super.visit(plainSelect, context);
        }

        @Override
        public <S> Void visit(SetOperationList setOperationList, S context) {
            rejectLocking(setOperationList.getForMode() != null);
            rejectDataModifyingCte(setOperationList);
            return super.visit(setOperationList, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
            rejectDataModifyingCte(parenthesedSelect);
            return super.visit(parenthesedSelect, context);
        }

        private void rejectLocking(boolean locking) {
            if (locking) {
                throw new SqlRejectedException(SqlRejectedException.Reason.LOCKING_CLAUSE,
                        "FOR UPDATE and FOR SHARE take row locks, which would block other learners "
                        + "running their own queries against the same sample data. The locking "
                        + "lesson explains what they do without needing to hold one.");
            }
        }

        @Override
        public <S> Void visit(Table table, S context) {
            List<String> parts = table.getNameParts();
            if (parts != null && parts.size() > 2) {
                tooManyParts.add(table.getFullyQualifiedName());
            }
            String schema = table.getSchemaName();
            if (schema != null && !schema.isBlank()) {
                schemas.add(unquote(schema));
            }
            return super.visit(table, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName();
            if (name != null) {
                // A function may be written pg_catalog.pg_sleep(1); compare on
                // the last part so qualifying it is not a way around the list.
                String bare = name.substring(name.lastIndexOf('.') + 1);
                functions.add(unquote(bare).toLowerCase(Locale.ROOT));
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    schemas.add(unquote(name.substring(0, dot)));
                }
            }
            return super.visit(function, context);
        }

        private static String unquote(String value) {
            String v = value.trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                return v.substring(1, v.length() - 1);
            }
            return v;
        }
    }
}

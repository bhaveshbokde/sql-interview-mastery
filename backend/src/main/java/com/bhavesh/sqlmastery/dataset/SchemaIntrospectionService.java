package com.bhavesh.sqlmastery.dataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Describes the learning schemas so the UI can show a browsable table list.
 *
 * <p>It reads {@code information_schema} and {@code pg_catalog} rather than
 * carrying a hand-written copy of the dataset, which means the panel cannot go
 * stale when a migration changes a column. The descriptions come from
 * PostgreSQL's own comment facility for the same reason.
 *
 * <p>This runs on the application connection, not the learner one. The learner
 * role deliberately has no access to the catalogue beyond what it needs, and
 * giving the UI its table list is the application's job, not something a
 * learner query should have to do.
 */
@Service
public class SchemaIntrospectionService {

    private final JdbcTemplate jdbc;

    public SchemaIntrospectionService(JdbcTemplate appJdbcTemplate) {
        this.jdbc = appJdbcTemplate;
    }

    /**
     * @param schema one of the learning schemas; the caller has already checked
     *               it against the allow-list
     */
    public List<TableInfo> describe(String schema) {
        Map<String, List<ColumnInfo>> columnsByTable = new LinkedHashMap<>();
        jdbc.query("""
                select c.table_name,
                       c.column_name,
                       c.data_type,
                       c.is_nullable = 'YES'                      as nullable,
                       c.column_default,
                       coalesce(pk.is_pk, false)                  as is_primary_key,
                       fk.references_table
                from information_schema.columns c
                left join (
                    select kcu.table_name, kcu.column_name, true as is_pk
                    from information_schema.table_constraints tc
                    join information_schema.key_column_usage kcu
                      on kcu.constraint_name = tc.constraint_name
                     and kcu.table_schema = tc.table_schema
                    where tc.table_schema = ? and tc.constraint_type = 'PRIMARY KEY'
                ) pk on pk.table_name = c.table_name and pk.column_name = c.column_name
                left join (
                    select kcu.table_name, kcu.column_name,
                           ccu.table_name || '.' || ccu.column_name as references_table
                    from information_schema.table_constraints tc
                    join information_schema.key_column_usage kcu
                      on kcu.constraint_name = tc.constraint_name
                     and kcu.table_schema = tc.table_schema
                    join information_schema.constraint_column_usage ccu
                      on ccu.constraint_name = tc.constraint_name
                     and ccu.table_schema = tc.table_schema
                    where tc.table_schema = ? and tc.constraint_type = 'FOREIGN KEY'
                ) fk on fk.table_name = c.table_name and fk.column_name = c.column_name
                where c.table_schema = ?
                order by c.table_name, c.ordinal_position
                """,
                ps -> {
                    ps.setString(1, schema);
                    ps.setString(2, schema);
                    ps.setString(3, schema);
                },
                rs -> {
                    columnsByTable
                            .computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                            .add(new ColumnInfo(
                                    rs.getString("column_name"),
                                    rs.getString("data_type"),
                                    rs.getBoolean("nullable"),
                                    rs.getBoolean("is_primary_key"),
                                    rs.getString("references_table"),
                                    rs.getString("column_default")));
                });

        Map<String, String> comments = tableComments(schema);
        Map<String, Long> rowCounts = rowCounts(schema, columnsByTable.keySet());

        List<TableInfo> tables = new ArrayList<>(columnsByTable.size());
        columnsByTable.forEach((name, columns) ->
                tables.add(new TableInfo(name, comments.get(name),
                        rowCounts.getOrDefault(name, 0L), columns)));
        return tables;
    }

    private Map<String, String> tableComments(String schema) {
        Map<String, String> comments = new LinkedHashMap<>();
        jdbc.query("""
                select c.relname as table_name, obj_description(c.oid) as comment
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relkind = 'r'
                """,
                ps -> ps.setString(1, schema),
                rs -> {
                    String comment = rs.getString("comment");
                    if (comment != null) {
                        comments.put(rs.getString("table_name"), comment);
                    }
                });
        return comments;
    }

    /**
     * Exact row counts, one query per table.
     *
     * <p>{@code pg_class.reltuples} would be one query for all of them, but it
     * is a planner estimate that is {@code -1} until the table has been
     * analysed and wrong after a bulk load. A learner comparing the panel's
     * count against {@code select count(*)} and finding a different number
     * would be right to distrust everything else on the screen.
     *
     * <p>The table names come from {@code information_schema}, not from the
     * caller, so interpolating them is safe — but they are still quoted with
     * {@code quote_ident} semantics by being wrapped in double quotes, because
     * "it came from the catalogue" is an argument that stops being true the
     * day someone passes this method a different list.
     */
    private Map<String, Long> rowCounts(String schema, Iterable<String> tableNames) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tableNames) {
            Long count = jdbc.queryForObject(
                    "select count(*) from \"" + schema.replace("\"", "\"\"") + "\".\""
                    + table.replace("\"", "\"\"") + "\"", Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return counts;
    }

    /** One table in the learning dataset. */
    public record TableInfo(String name, String description, long rowCount, List<ColumnInfo> columns) {
    }

    /** One column, with the relationships a learner needs in order to write a join. */
    public record ColumnInfo(
            String name,
            String dataType,
            boolean nullable,
            boolean primaryKey,
            String references,
            String defaultValue) {
    }
}

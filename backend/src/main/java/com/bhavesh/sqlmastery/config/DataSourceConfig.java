package com.bhavesh.sqlmastery.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The two database identities the application uses, and the reason there are
 * two of them.
 *
 * <p><b>app</b> owns the schemas, runs the Flyway migrations, reads the
 * curriculum and reseeds the learning dataset. It can write.
 *
 * <p><b>learner</b> executes SQL that a learner typed, and nothing else. It
 * holds exactly two privileges in the entire cluster: {@code USAGE} on the two
 * learning schemas and {@code SELECT} on their tables. It has no rights on the
 * {@code app} schema, so {@code select * from app.problem_solution} fails in
 * PostgreSQL rather than being caught by a filter someone could forget to
 * write. Its role definition also carries
 * {@code default_transaction_read_only = on}, which means a write is refused
 * by the server even if every other layer of this application were bypassed.
 *
 * <p>Keeping them as separate pools rather than one pool with a
 * {@code SET ROLE} is deliberate: {@code SET ROLE} can be reset by the session
 * that holds it, and a connection returned to a shared pool in the wrong state
 * is a class of bug this design does not have.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource appDataSource(
            @Value("${sqlmastery.datasource.url}") String url,
            @Value("${sqlmastery.datasource.app.username}") String username,
            @Value("${sqlmastery.datasource.app.password}") String password) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        ds.setPoolName("sqlmastery-app");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }

    @Bean
    public DataSource learnerDataSource(
            @Value("${sqlmastery.datasource.url}") String url,
            @Value("${sqlmastery.datasource.learner.username}") String username,
            @Value("${sqlmastery.datasource.learner.password}") String password,
            PlaygroundProperties playground) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();
        ds.setPoolName("sqlmastery-learner");
        // Deliberately small. A runaway number of concurrent playground queries
        // should be refused quickly, not allowed to open connections until the
        // server's max_connections is the thing that fails.
        ds.setMaximumPoolSize(Math.max(2, playground.maxConcurrent()));
        ds.setMinimumIdle(1);
        ds.setReadOnly(true);
        // A connection that cannot be handed out within this window means the
        // pool is saturated; failing fast turns that into a clear 429/503
        // rather than a request that hangs.
        ds.setConnectionTimeout(2_000L);
        // Belt and braces alongside the role-level setting: every connection
        // announces its own limits as soon as it is opened, so the guarantees
        // hold even against a cluster provisioned by hand without the script.
        ds.setConnectionInitSql(
                "set session characteristics as transaction read only;"
                + " set statement_timeout = " + playground.statementTimeoutMs() + ";"
                + " set idle_in_transaction_session_timeout = 10000;"
                + " set lock_timeout = 2000;"
                + " set search_path = learning");
        return ds;
    }

    @Bean
    public JdbcTemplate appJdbcTemplate(@Qualifier("appDataSource") DataSource appDataSource) {
        return new JdbcTemplate(appDataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate namedJdbcTemplate(JdbcTemplate appJdbcTemplate) {
        return new NamedParameterJdbcTemplate(appJdbcTemplate);
    }
}

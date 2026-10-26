package com.bhavesh.sqlmastery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the SQL Interview Mastery backend.
 *
 * <p>The application has one job that drives most of its design: it runs SQL
 * that a stranger typed. Everything in {@code com.bhavesh.sqlmastery.playground}
 * exists to make that safe, and the security model is written down in
 * {@code docs/SECURITY.md} rather than left to be inferred from the code.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SqlMasteryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlMasteryApplication.class, args);
    }
}

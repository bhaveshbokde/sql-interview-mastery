package com.bhavesh.sqlmastery;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application against the real test database.
 *
 * <p>There is no in-memory substitute here, and that is deliberate. The
 * subject of this application is PostgreSQL — jsonb containment, window
 * frames, DISTINCT ON, read-only transactions, SQLSTATE codes — and a test
 * against H2 would pass while proving nothing about any of it. Most of the
 * curriculum's own SQL does not even parse on another engine.
 *
 * <p>The cost is that these tests need a database. They are tagged
 * {@code integration} so they can be excluded when one is not available:
 *
 * <pre>./mvnw test -DexcludedGroups=integration</pre>
 *
 * <p>The database is created by {@code scripts/provision-db.sh} and the
 * migrations are applied by Flyway on the first test, which makes "do the
 * migrations apply to an empty database" something that is checked on every
 * run rather than assumed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public @interface IntegrationTest {
}

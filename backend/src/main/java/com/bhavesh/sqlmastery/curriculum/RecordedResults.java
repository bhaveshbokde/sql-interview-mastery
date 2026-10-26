package com.bhavesh.sqlmastery.curriculum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The recorded output of every query in the curriculum.
 *
 * <p>Nobody writes these by hand. {@code tools/verify_sql.py} executes each
 * statement against the real dataset and writes the results to
 * {@code curriculum/expected-output.json}; this class reads them back and the
 * loader merges them into the lessons and problems. A lesson example therefore
 * cannot display a result table that its own query does not produce, which is
 * the single most common defect in written SQL tutorials and the hardest to
 * notice, because a plausible-looking wrong result reads exactly like a right
 * one.
 *
 * <p>Entries are keyed by a digest of the schema and the SQL rather than by
 * position in the file. That makes the pairing unforgeable in the direction
 * that matters: an output can only be attached to the exact statement that
 * produced it, so editing a query without re-running the verifier removes its
 * recorded output rather than silently inheriting the old one. The loader
 * treats a missing output as a startup failure.
 */
@Component
public class RecordedResults {

    private static final String RESOURCE = "curriculum/expected-output.json";

    private final Map<String, QueryResult> byKey;

    /**
     * The constructor Spring uses.
     *
     * <p>Annotated explicitly because this class has two constructors, and
     * with more than one candidate Spring does not guess — it looks for a
     * no-argument constructor and fails when there is none. The second
     * constructor exists for tests, which supply the map directly rather than
     * going through the classpath resource.
     */
    @Autowired
    public RecordedResults(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    RecordedResults(Map<String, QueryResult> byKey) {
        this.byKey = byKey;
    }

    private static Map<String, QueryResult> load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            // An empty map rather than a failure here: the loader reports the
            // missing outputs one statement at a time, which tells an author
            // what to do. A stack trace about a missing file does not.
            return Map.of();
        }
        try (var in = resource.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<Map<String, QueryResult>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    /** @return the recorded result for this statement, or null if never recorded */
    public QueryResult find(String schema, String sql) {
        return byKey.get(key(schema, sql));
    }

    public int size() {
        return byKey.size();
    }

    /**
     * The key for a statement.
     *
     * <p>Whitespace is normalised so that reindenting a query in the YAML does
     * not invalidate its recorded output, but every other character matters —
     * changing a column, a filter or a sort order produces a different key and
     * therefore no recorded output, which is the intended behaviour.
     *
     * <p>The schema and the statement are joined by {@link #KEY_SEPARATOR},
     * written as the escape {@code "\0"} rather than as a raw character.
     * It was a literal NUL byte in the file until it cost an afternoon: the
     * separator is invisible in every editor and in every diff, so a
     * reimplementation of this function reads the source, sees a space, and
     * produces keys that silently match nothing.
     *
     * <p>A divergence here looks like "every recorded output is missing" and
     * gets investigated as a file problem rather than as an encoding one,
     * which is why {@code RecordedResultsKeyTest} pins the function against a
     * known digest.
     */
    public static String key(String schema, String sql) {
        String normalisedSchema = schema == null || schema.isBlank() ? "learning" : schema.trim();
        String normalisedSql = sql.strip().replaceAll("\\s+", " ");
        return sha256(normalisedSchema + KEY_SEPARATOR + normalisedSql);
    }

    /**
     * Separates the schema from the statement inside a key.
     *
     * <p>NUL, because it cannot occur in either part, so no schema name and
     * statement can collide with a different pair. Spelled with the octal
     * escape so it is visible in the source.
     */
    static final String KEY_SEPARATOR = "\0";

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}

package com.bhavesh.sqlmastery.dataset;

import java.util.List;
import java.util.Locale;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import com.bhavesh.sqlmastery.playground.SqlRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learning dataset: what is in it, and how to put it back.
 *
 * <p>Note what this controller does not offer. There is no endpoint that runs a
 * statement with write privileges, no endpoint that takes SQL at all, and no
 * way to reset one table rather than the whole dataset. Reset is a verb with no
 * arguments, which is the entire reason it can be exposed without
 * authentication on an application whose other endpoint runs SQL from
 * strangers.
 */
@RestController
@RequestMapping("/api/dataset")
@Tag(name = "Dataset", description = "Inspect and restore the disposable learning dataset.")
public class DatasetController {

    private final DatasetResetService resetService;
    private final SchemaIntrospectionService introspection;
    private final PlaygroundProperties properties;

    public DatasetController(
            DatasetResetService resetService,
            SchemaIntrospectionService introspection,
            PlaygroundProperties properties) {
        this.resetService = resetService;
        this.introspection = introspection;
        this.properties = properties;
    }

    @GetMapping("/schema")
    @Operation(summary = "Tables, columns, keys and exact row counts",
               description = "Read from information_schema, so it cannot disagree with the database.")
    public List<SchemaIntrospectionService.TableInfo> schema(
            @RequestParam(defaultValue = "learning") String schema) {
        return introspection.describe(requireAllowedSchema(schema));
    }

    @PostMapping("/reset")
    @Operation(summary = "Restore the learning dataset to its published state",
               description = """
                       Truncates and repopulates both learning schemas from the committed seed
                       file. Takes no parameters: the request is a verb, not a payload.

                       Rate-limited by a short cooldown, because the dataset is shared and
                       truncating it repeatedly would disrupt anyone else reading from it.""")
    public ResponseEntity<?> reset() {
        try {
            return ResponseEntity.ok(resetService.reset(false));
        } catch (DatasetResetService.ResetTooSoonException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                    .body(new Message("RESET_TOO_SOON", e.getMessage()));
        }
    }

    /**
     * Validates a schema name from the query string against the allow-list.
     *
     * <p>Returns the matching entry from configuration rather than the caller's
     * string, so what reaches {@link SchemaIntrospectionService} is a value
     * this application chose.
     */
    private String requireAllowedSchema(String requested) {
        String normalised = requested.trim().toLowerCase(Locale.ROOT);
        for (String allowed : properties.allowedSchemas()) {
            if (allowed.equals(normalised)) {
                return allowed;
            }
        }
        throw new SqlRejectedException(SqlRejectedException.Reason.SCHEMA_NOT_ALLOWED,
                "There is no dataset called \"" + requested + "\". Available datasets: "
                + properties.allowedSchemas().stream().sorted().toList() + ".");
    }

    /** A short machine-readable failure. */
    public record Message(String code, String message) {
    }
}

package com.bhavesh.sqlmastery.playground;

import java.sql.SQLException;

import com.bhavesh.sqlmastery.config.PlaygroundProperties;
import com.bhavesh.sqlmastery.playground.dto.QueryError;
import com.bhavesh.sqlmastery.playground.dto.QueryRequest;
import com.bhavesh.sqlmastery.playground.dto.QueryResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The interactive SQL editor's endpoint. */
@RestController
@RequestMapping("/api/playground")
@Tag(name = "Playground", description = "Runs read-only SQL against the learning dataset.")
public class PlaygroundController {

    private final QueryExecutionService executionService;
    private final QueryRateLimiter rateLimiter;
    private final SqlErrorMapper errorMapper;
    private final PlaygroundProperties properties;

    public PlaygroundController(
            QueryExecutionService executionService,
            QueryRateLimiter rateLimiter,
            SqlErrorMapper errorMapper,
            PlaygroundProperties properties) {
        this.executionService = executionService;
        this.rateLimiter = rateLimiter;
        this.errorMapper = errorMapper;
        this.properties = properties;
    }

    @PostMapping("/query")
    @Operation(
            summary = "Execute a read-only query",
            description = """
                    Accepts exactly one SELECT statement, which may use CTEs, window functions,
                    joins, set operations and aggregates. Anything that writes, locks rows,
                    creates objects or names a schema outside the learning dataset is refused
                    before it reaches the database.

                    A query that PostgreSQL rejects comes back as 400 with the SQLSTATE in
                    `code` and, where one exists, an explanation of what that error usually
                    means. A failed query never leaves the dataset modified: everything runs in
                    a read-only transaction that is rolled back either way.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The query ran"),
        @ApiResponse(responseCode = "400", description = "Refused by the validator, or rejected by PostgreSQL"),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
        @ApiResponse(responseCode = "503", description = "Too many queries running at once")
    })
    public ResponseEntity<?> run(@Valid @RequestBody QueryRequest request, HttpServletRequest http) {
        String clientKey = clientKey(http);
        if (!rateLimiter.tryAcquire(clientKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "5")
                    .body(new QueryError("RATE_LIMITED",
                            "Too many queries in a short time.",
                            "The playground allows " + properties.ratePerMinute()
                            + " queries a minute per client. The allowance refills continuously, "
                            + "so a few seconds is enough.",
                            null, null));
        }
        try {
            QueryResult result = executionService.execute(request.sql(), request.schema());
            return ResponseEntity.ok(result);
        } catch (SqlRejectedException e) {
            return ResponseEntity.badRequest()
                    .body(new QueryError(e.reason().name(), e.getMessage(), null, null, null));
        } catch (QueryExecutionService.PlaygroundBusyException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "2")
                    .body(new QueryError("BUSY", e.getMessage(), null, null, null));
        } catch (SQLException e) {
            return ResponseEntity.badRequest().body(errorMapper.toQueryError(e));
        }
    }

    @GetMapping("/limits")
    @Operation(summary = "The limits applied to every query",
               description = "Shown in the editor so a learner knows why a result was cut off.")
    public PlaygroundProperties limits() {
        return properties;
    }

    /**
     * Identifies a caller for rate limiting.
     *
     * <p>A client-supplied header first, falling back to the remote address.
     * Both are trivially spoofable, which is fine: the limiter exists to stop
     * one buggy tab from exhausting the connection pool, not to stop an
     * adversary from reading sample data they are already being shown.
     */
    private String clientKey(HttpServletRequest request) {
        String supplied = request.getHeader("X-Client-Id");
        if (supplied != null && !supplied.isBlank()) {
            return "client:" + supplied.substring(0, Math.min(64, supplied.length()));
        }
        return "addr:" + request.getRemoteAddr();
    }
}

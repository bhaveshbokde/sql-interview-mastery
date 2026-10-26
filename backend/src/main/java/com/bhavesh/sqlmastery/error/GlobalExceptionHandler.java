package com.bhavesh.sqlmastery.error;

import java.util.stream.Collectors;

import com.bhavesh.sqlmastery.dataset.DatasetResetService;
import com.bhavesh.sqlmastery.playground.SqlRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns the exceptions the application throws deliberately into responses, and
 * everything else into a 500 that says nothing useful to a caller.
 *
 * <p>The asymmetry is the point. A {@link SqlRejectedException} carries a
 * message written to be read by a learner, so it is returned verbatim. An
 * unexpected exception might carry a connection string, a file path or a
 * fragment of an internal query, so its message is replaced and the detail goes
 * to the log instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A machine-readable code plus a human sentence. */
    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(SqlRejectedException.class)
    public ResponseEntity<ApiError> onRejected(SqlRejectedException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.reason().name(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", detail));
    }

    @ExceptionHandler(DatasetResetService.DatasetResetException.class)
    public ResponseEntity<ApiError> onResetFailed(DatasetResetService.DatasetResetException e) {
        log.error("Dataset reset failed", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("RESET_FAILED",
                        "The dataset could not be restored. The previous contents are intact: "
                        + "the reseed runs in a transaction and was rolled back."));
    }

    /**
     * The exceptions Spring MVC raises for a malformed request.
     *
     * <p>Without this, the catch-all below swallowed them: an unknown URL and a
     * truncated JSON body both came back as 500 INTERNAL_ERROR, and both were
     * written to the log with a stack trace as though the server had failed.
     * Neither is a server failure. Reporting them as one is wrong twice over —
     * the caller cannot tell a typo in a URL from an outage, and a log full of
     * other people's typos is a log nobody reads.
     *
     * <p>They are listed explicitly rather than handled by extending
     * {@code ResponseEntityExceptionHandler}, because that base class answers
     * with RFC 7807 ProblemDetail and every other response here is an
     * {@link ApiError}. One response shape across the whole API is worth more
     * than the handful of lines it costs.
     */
    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class,
    })
    public ResponseEntity<ApiError> onNoHandler(Exception e) {
        log.debug("No handler for request", e);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "No such endpoint."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadable(HttpMessageNotReadableException e) {
        // The message is not echoed. Jackson's parse errors quote the input,
        // and the input is whatever the caller sent.
        log.debug("Unreadable request body", e);
        return ResponseEntity.badRequest()
                .body(new ApiError("MALFORMED_REQUEST",
                        "The request body could not be read as JSON."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> onBadMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED",
                        "That endpoint does not accept " + e.getMethod() + "."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> onBadMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE",
                        "Send application/json."));
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
    })
    public ResponseEntity<ApiError> onBadParameter(Exception e) {
        String detail = e instanceof MissingServletRequestParameterException missing
                ? "Missing required parameter '" + missing.getParameterName() + "'."
                : "A request parameter has the wrong type.";
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", detail));
    }

    /**
     * Anything thrown with a status already attached keeps it. The message is
     * dropped unless the status is a client error, for the same reason the
     * catch-all drops it.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> onStatusException(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("Server error raised with an explicit status", e);
            return ResponseEntity.status(status)
                    .body(new ApiError("INTERNAL_ERROR",
                            "Something went wrong on the server. The failure has been logged."));
        }
        String reason = e.getReason() == null ? status.getReasonPhrase() : e.getReason();
        return ResponseEntity.status(status).body(new ApiError(status.name(), reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception e) {
        // Logged with the stack trace, returned without it. An exception
        // message is written for whoever maintains the code, not for whoever
        // is calling the API, and it is not always free of internal detail.
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR",
                        "Something went wrong on the server. The failure has been logged."));
    }
}

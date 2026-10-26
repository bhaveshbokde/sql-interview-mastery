package com.bhavesh.sqlmastery.error;

import java.util.stream.Collectors;

import com.bhavesh.sqlmastery.dataset.DatasetResetService;
import com.bhavesh.sqlmastery.playground.SqlRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

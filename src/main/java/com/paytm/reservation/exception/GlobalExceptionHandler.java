package com.paytm.reservation.exception;

import com.paytm.reservation.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SeatReservationException.class)
    public ResponseEntity<ErrorResponse> handleSeatReservationException(SeatReservationException ex) {
        String requestId = getRequestId();
        log.warn("Domain exception: status={}, reason={}, message={}, requestId={}",
                ex.getStatus(), ex.getReason(), ex.getMessage(), requestId);

        ErrorResponse errorResponse = new ErrorResponse(
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                ex.getReason(),
                ex.getStatus().value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(ex.getStatus()).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String requestId = getRequestId();
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation failure: message={}, requestId={}", detail, requestId);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                detail.isEmpty() ? "Validation failed" : detail,
                "validation_error",
                HttpStatus.BAD_REQUEST.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex) {
        String requestId = getRequestId();
        log.warn("Malformed HTTP request body: message={}, requestId={}", ex.getMessage(), requestId);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Malformed JSON request payload",
                "malformed_json",
                HttpStatus.BAD_REQUEST.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLockingFailure(Exception ex) {
        String requestId = getRequestId();
        log.warn("Lock acquisition contention under load: message={}, requestId={}", ex.getMessage(), requestId);

        // Convert concurrency lock conflict to 409 Conflict (zero 5xx under load)
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.getReasonPhrase(),
                "Seat lock contention under heavy concurrency. Please retry.",
                "seat_taken",
                HttpStatus.CONFLICT.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String requestId = getRequestId();
        log.warn("Data integrity conflict under concurrency: message={}, requestId={}", ex.getMessage(), requestId);

        // Convert duplicate key / unique constraint conflicts to 409 Conflict
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.getReasonPhrase(),
                "Conflict occurred while processing reservation.",
                "conflict",
                HttpStatus.CONFLICT.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        String requestId = getRequestId();
        log.warn("Resource not found: message={}, requestId={}", ex.getMessage(), requestId);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                "not_found",
                HttpStatus.NOT_FOUND.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        String requestId = getRequestId();
        log.warn("Method not allowed: message={}, requestId={}", ex.getMessage(), requestId);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                ex.getMessage(),
                "method_not_allowed",
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        String requestId = getRequestId();
        log.error("Unhandled exception caught by global handler: requestId={}", requestId, ex);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected internal error occurred.",
                "internal_error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                requestId,
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    private String getRequestId() {
        String rid = MDC.get("request_id");
        return (rid != null && !rid.isBlank()) ? rid : "none";
    }
}

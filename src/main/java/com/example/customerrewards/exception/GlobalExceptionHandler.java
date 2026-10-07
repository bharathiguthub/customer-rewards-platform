package com.example.customerrewards.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCustomerNotFound(
            CustomerNotFoundException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.NOT_FOUND,
            "CUSTOMER_NOT_FOUND",
            request,
            response
        );
    }

    @ExceptionHandler(InvalidRewardPointsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRewardPoints(
            InvalidRewardPointsException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.BAD_REQUEST,
            "INVALID_REWARD_POINTS",
            request,
            response
        );
    }

    @ExceptionHandler(InsufficientRewardBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(
            InsufficientRewardBalanceException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.CONFLICT,
            "INSUFFICIENT_REWARD_BALANCE",
            request,
            response
        );
    }

    @ExceptionHandler(RewardConcurrencyException.class)
    public ResponseEntity<ErrorResponse> handleRewardConcurrency(
            RewardConcurrencyException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.CONFLICT,
            "REWARD_CONCURRENCY_CONFLICT",
            request,
            response
        );
    }

    @ExceptionHandler(DuplicateCustomerEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(
            DuplicateCustomerEmailException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.CONFLICT,
            "DUPLICATE_CUSTOMER_EMAIL",
            request,
            response
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        return buildErrorResponse(
            ex.getMessage(),
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            request,
            response
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        List<String> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            errors.add(error.getField() + ": " + error.getDefaultMessage())
        );
        ex.getBindingResult().getGlobalErrors().forEach(error ->
            errors.add(error.getObjectName() + ": " + error.getDefaultMessage())
        );

        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }

        response.addHeader(CORRELATION_ID_HEADER, correlationId);

        ErrorResponse errorResponse = new ErrorResponse(
            correlationId,
            HttpStatus.BAD_REQUEST.value(),
            "VALIDATION_FAILED",
            "Request validation failed",
            Instant.now(),
            errors.isEmpty() ? null : errors
        );

        log.warn("Validation error: {}", errorResponse);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        log.error("Unhandled exception", ex);
        return buildErrorResponse(
            "An internal server error occurred",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_SERVER_ERROR",
            request,
            response
        );
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            String message,
            HttpStatus status,
            String code,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }

        response.addHeader(CORRELATION_ID_HEADER, correlationId);

        ErrorResponse errorResponse = new ErrorResponse(
            correlationId,
            status.value(),
            code,
            message,
            Instant.now(),
            null
        );

        log.warn("Error response: {} {}", status, errorResponse);
        return ResponseEntity.status(status).body(errorResponse);
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record ErrorResponse(
    String correlationId,
    int status,
    String code,
    String message,
    Instant timestamp,
    List<String> details
) {}

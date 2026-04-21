package com.dev.auth_server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

        @ExceptionHandler(InvalidCredentialsException.class)
        public ResponseEntity<?> handleInvalidCredentials(InvalidCredentialsException ex) {

                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of(
                                                "timestamp", LocalDateTime.now(),
                                                "error", "Unauthorized",
                                                "message", ex.getMessage()));
        }

        @ExceptionHandler(TokenException.class)
        public ResponseEntity<?> handleTokenException(TokenException ex) {

                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of(
                                                "timestamp", LocalDateTime.now(),
                                                "error", "Unauthorized",
                                                "message", ex.getMessage()));
        }

        @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<?> handleMethodNotSupported(
                        org.springframework.web.HttpRequestMethodNotSupportedException ex) {
                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                                .body(Map.of(
                                                "timestamp", LocalDateTime.now(),
                                                "error", "Method Not Allowed",
                                                "message", ex.getMessage()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<?> handleAll(Exception ex) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of(
                                                "timestamp", LocalDateTime.now(),
                                                "error", ex.getClass().getName(),
                                                "message", ex.getMessage()));
        }

        @ExceptionHandler(UserNotFoundException.class)
        public ResponseEntity<?> handleUserNotFound(UserNotFoundException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of(
                                                "timestamp", LocalDateTime.now(),
                                                "error", "Not Found",
                                                "message", ex.getMessage()));
        }

        @ExceptionHandler(RateLimitExceededException.class)
        public ResponseEntity<?> handleRateLimit(RateLimitExceededException ex) {

                return ResponseEntity.status(429)
                                .body(Map.of(
                                                "error", "Too Many Requests",
                                                "message", ex.getMessage()));
        }

}
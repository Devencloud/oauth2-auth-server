package com.dev.auth_server.exception;

public class TokenException extends RuntimeException {
    public TokenException(String message) {
        super(message);
    }
}
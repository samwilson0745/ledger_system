package com.soham.ledger.security;

public record LoginResponse(String token, String tokenType, long expiresInMinutes) {
}

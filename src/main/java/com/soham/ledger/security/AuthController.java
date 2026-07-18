package com.soham.ledger.security;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthProperties authProperties;
    private final JwtService jwtService;

    public AuthController(AuthProperties authProperties, JwtService jwtService) {
        this.authProperties = authProperties;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        boolean matched = authProperties.users().stream()
                .anyMatch(u -> u.username().equals(request.username()) && constantTimeEquals(u.password(), request.password()));

        if (!matched) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(request.username());
        return new LoginResponse(token, "Bearer", jwtService.expirationMinutes());
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}

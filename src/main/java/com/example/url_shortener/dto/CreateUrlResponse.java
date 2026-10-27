package com.example.url_shortener.dto;

import java.time.Instant;

public record CreateUrlResponse(String shortCode, String originalUrl, Instant expiresAt) {
}

package com.example.url_shortener.model;

import java.time.Instant;

public record ShortUrl(
        String shortCode,
        String originalUrl,
        String ownerId,
        Instant createdAt,
        Instant expiresAt,
        boolean isDeleted
) {
    public boolean isAccessible(Instant now) {
        return !isDeleted && (expiresAt == null || now.isBefore(expiresAt));
    }
}

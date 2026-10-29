package com.example.url_shortener.model;

import java.time.Duration;

public record IdempotencyRecord(String originalUrl, Duration ttl, ShortUrl result) {
}

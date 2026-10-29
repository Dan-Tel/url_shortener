package com.example.url_shortener.model;

public record IdempotencyKey(String ownerId, String key) {
}

package com.example.url_shortener.repository;

import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import com.example.url_shortener.model.ShortUrl;

import java.time.Duration;
import java.util.function.Supplier;

public interface IdempotencyStore {
    IdempotencyRecord getOrCreate(
            IdempotencyKey key,
            String originalUrl,
            Duration ttl,
            Supplier<ShortUrl> creator
    );
}
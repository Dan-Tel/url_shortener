package com.example.url_shortener.repository;

import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import com.example.url_shortener.model.ShortUrl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Repository
@Profile("memory")
public class IdempotencyRepository implements IdempotencyStore {
    private final ConcurrentHashMap<IdempotencyKey, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public IdempotencyRecord getOrCreate(IdempotencyKey key, String originalUrl, Duration ttl, Supplier<ShortUrl> creator) {
        return records.computeIfAbsent(key, k -> new IdempotencyRecord(originalUrl, ttl, creator.get()));
    }
}
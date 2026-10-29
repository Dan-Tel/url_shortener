package com.example.url_shortener.repository;

import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Repository
public class IdempotencyRepository {
    private final ConcurrentHashMap<IdempotencyKey, IdempotencyRecord> storage = new ConcurrentHashMap<>();

    public IdempotencyRecord getOrCreate(
            IdempotencyKey key,
            Supplier<IdempotencyRecord> creator
    ) {
        return storage.computeIfAbsent(key, (k -> creator.get()));
    }
}

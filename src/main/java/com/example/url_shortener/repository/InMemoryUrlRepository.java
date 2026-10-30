package com.example.url_shortener.repository;

import com.example.url_shortener.model.ShortUrl;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
@Profile("memory")
public class InMemoryUrlRepository implements UrlRepository {
    private final ConcurrentHashMap<String, ShortUrl> storage = new ConcurrentHashMap<>();

    public boolean saveIfAbsent(ShortUrl url) {
        return storage.putIfAbsent(url.shortCode(), url) == null;
    }

    public Optional<ShortUrl> findByCode(String shortCode) {
        return Optional.ofNullable(storage.get(shortCode));
    }

    public boolean softDelete(String shortCode, String ownerId) {
        AtomicBoolean success = new AtomicBoolean(false);

        storage.computeIfPresent(shortCode, (String key, ShortUrl oldValue) -> {
           if (oldValue.ownerId().equals(ownerId) && !oldValue.isDeleted()) {
               success.set(true);

               return new ShortUrl(
                       oldValue.shortCode(),
                       oldValue.originalUrl(),
                       oldValue.ownerId(),
                       oldValue.createdAt(),
                       oldValue.expiresAt(),
                       true
               );
           }

           return oldValue;
        });

        return success.get();
    }
}

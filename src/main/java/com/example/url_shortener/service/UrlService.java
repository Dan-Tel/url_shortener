package com.example.url_shortener.service;

import com.example.url_shortener.exception.IdempotencyConflictException;
import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import com.example.url_shortener.model.ShortUrl;
import com.example.url_shortener.repository.IdempotencyRepository;
import com.example.url_shortener.repository.InMemoryUrlRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class UrlService {
    private static final int MAX_TRIES = 5;
    private final ShortCodeGenerator generator;
    private final InMemoryUrlRepository repository;
    private final IdempotencyRepository idempotencyRepository;

    public UrlService(ShortCodeGenerator generator, InMemoryUrlRepository repository, IdempotencyRepository idempotencyRepository) {
        this.generator = generator;
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
    }

    public ShortUrl createUrl(String originalUrl, String ownerId, Duration ttl) {
        if (ttl == null) {
            throw new IllegalArgumentException("TTL обязателен");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL должен быть положительным");
        }

        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plus(ttl);

        for (int i = 0; i < MAX_TRIES; i++) {
            String shortCode = generator.generate();

            ShortUrl shortUrl = new ShortUrl(shortCode, originalUrl, ownerId, createdAt, expiresAt, false);
            if (repository.saveIfAbsent(shortUrl)) {
                return shortUrl;
            }
        }

        throw new IllegalStateException("Не получилось сгенерировать shortUrl");
    }

    public ShortUrl createIdempotentUrl(String originalUrl, String ownerId, Duration ttl, String idempotencyKey) {
        IdempotencyKey key = new IdempotencyKey(ownerId, idempotencyKey);
        IdempotencyRecord savedRecord = idempotencyRepository.getOrCreate(key, () -> new IdempotencyRecord(originalUrl, ttl, createUrl(originalUrl, ownerId, ttl)));

        if (!savedRecord.originalUrl().equals(originalUrl) || !savedRecord.ttl().equals(ttl)) {
            throw new IdempotencyConflictException("Idempotency-Key уже создан с другими параметрами");
        }

        return savedRecord.result();
    }

    public String resolveUrl(String shortCode) {
        ShortUrl shortUrl = repository.findByCode(shortCode).orElseThrow(() -> new NoSuchElementException("URL с таким shortCode не существует"));

        if (!shortUrl.isAccessible(Instant.now())) {
            throw new NoSuchElementException("URL с таким shortCode удален или время действия истекло");
        }

        return shortUrl.originalUrl();
    }

    public boolean deleteUrl(String shortCode, String ownerId) {
        return repository.softDelete(shortCode, ownerId);
    }
}

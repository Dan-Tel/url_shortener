package com.example.url_shortener.service;

import com.example.url_shortener.exception.IdempotencyConflictException;
import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import com.example.url_shortener.model.ShortUrl;
import com.example.url_shortener.repository.IdempotencyStore;
import com.example.url_shortener.repository.UrlRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class UrlService {
    private static final int MAX_TRIES = 5;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_OWNER_ID_LENGTH = 128;

    private final ShortCodeGenerator generator;
    private final UrlRepository repository;
    private final IdempotencyStore idempotencyStore;

    public UrlService(
            ShortCodeGenerator generator,
            UrlRepository repository,
            IdempotencyStore idempotencyStore
    ) {
        this.generator = generator;
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
    }

    public ShortUrl createUrl(
            String originalUrl,
            String ownerId,
            Duration ttl
    ) {
        validateOriginalUrl(originalUrl);
        validateOwnerId(ownerId);

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

            ShortUrl shortUrl = new ShortUrl(
                    shortCode,
                    originalUrl,
                    ownerId,
                    createdAt,
                    expiresAt,
                    false
            );

            if (repository.saveIfAbsent(shortUrl)) {
                return shortUrl;
            }
        }

        throw new IllegalStateException("Не получилось сгенерировать shortUrl");
    }

    public ShortUrl createIdempotentUrl(
            String originalUrl,
            String ownerId,
            Duration ttl,
            String idempotencyKey
    ) {
        validateOriginalUrl(originalUrl);
        validateOwnerId(ownerId);

        IdempotencyKey key = new IdempotencyKey(ownerId, idempotencyKey);

        IdempotencyRecord savedRecord = idempotencyStore.getOrCreate(
                key,
                originalUrl,
                ttl,
                () -> createUrl(originalUrl, ownerId, ttl)
        );

        if (!savedRecord.originalUrl().equals(originalUrl)
                || !savedRecord.ttl().equals(ttl)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key уже создан с другими параметрами"
            );
        }

        return savedRecord.result();
    }

    public String resolveUrl(String shortCode) {
        ShortUrl shortUrl = repository.findByCode(shortCode)
                .orElseThrow(() -> new NoSuchElementException(
                        "URL с таким shortCode не существует"
                ));

        if (!shortUrl.isAccessible(Instant.now())) {
            throw new NoSuchElementException(
                    "URL с таким shortCode удален или время действия истекло"
            );
        }

        return shortUrl.originalUrl();
    }

    public boolean deleteUrl(String shortCode, String ownerId) {
        validateOwnerId(ownerId);
        return repository.softDelete(shortCode, ownerId);
    }

    private static void validateOriginalUrl(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Original URL не может быть пустым"
            );
        }

        if (originalUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "Original URL не может превышать 2048 символов"
            );
        }
    }

    private static void validateOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Owner ID не может быть пустым"
            );
        }

        if (ownerId.length() > MAX_OWNER_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Owner ID не может превышать 128 символов"
            );
        }
    }
}

package com.example.url_shortener.service;

import com.example.url_shortener.model.ShortUrl;
import com.example.url_shortener.repository.InMemoryUrlRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;

public class UrlService {
    private static final int MAX_TRIES = 5;
    private final ShortCodeGenerator generator;
    private final InMemoryUrlRepository repository;

    public UrlService(ShortCodeGenerator generator, InMemoryUrlRepository repository) {
        this.generator = generator;
        this.repository = repository;
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

    public static void main(String[] args) {
        InMemoryUrlRepository repository = new InMemoryUrlRepository();
        ShortCodeGenerator generator = new ShortCodeGenerator();

        UrlService service = new UrlService(generator, repository);

        ShortUrl shortUrl = service.createUrl("https://youtube.com", "Daniyar", Duration.ofHours(24));

        System.out.println(service.resolveUrl(shortUrl.shortCode()));
        System.out.println(shortUrl.shortCode());
        System.out.println(shortUrl.originalUrl());
        System.out.println(shortUrl.createdAt());
        System.out.println(shortUrl.expiresAt());
    }
}

package com.example.url_shortener.repository;

import com.example.url_shortener.model.ShortUrl;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

@Repository
@Primary
@Profile("postgres & redis")
public class CachedUrlRepository implements UrlRepository {

    private static final Logger log =
            Logger.getLogger(CachedUrlRepository.class.getName());

    private static final Duration MAX_CACHE_TTL = Duration.ofSeconds(59);
    private static final String KEY_PREFIX = "shorturl:";

    private final PostgresUrlRepository postgresRepository;
    private final StringRedisTemplate redisTemplate;

    public CachedUrlRepository(
            PostgresUrlRepository postgresRepository,
            StringRedisTemplate redisTemplate
    ) {
        this.postgresRepository = postgresRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean saveIfAbsent(ShortUrl url) {
        return postgresRepository.saveIfAbsent(url);
    }

    @Override
    public Optional<ShortUrl> findByCode(String shortCode) {
        String key = KEY_PREFIX + shortCode;

        try {
            Map<Object, Object> cached =
                    redisTemplate.opsForHash().entries(key);

            if (!cached.isEmpty()) {
                ShortUrl url = fromHash(shortCode, cached);

                if (url.isAccessible(Instant.now())) {
                    return Optional.of(url);
                }

                redisTemplate.delete(key);
            }
        } catch (DataAccessException e) {
            log.warning("Redis ошибка чтения: " + e.getMessage());
        }

        Optional<ShortUrl> result =
                postgresRepository.findByCode(shortCode);

        result.ifPresent(url -> {
            Instant now = Instant.now();

            if (!url.isAccessible(now)) {
                return;
            }

            Duration remaining = Duration.between(now, url.expiresAt());

            Duration ttl = remaining.compareTo(MAX_CACHE_TTL) < 0
                    ? remaining
                    : MAX_CACHE_TTL;

            if (ttl.isZero() || ttl.isNegative()) {
                return;
            }

            try {
                redisTemplate.opsForHash().putAll(key, toHash(url));
                redisTemplate.expire(key, ttl);
            } catch (DataAccessException e) {
                log.warning("Redis ошибка записи: " + e.getMessage());
            }
        });

        return result;
    }

    @Override
    public boolean softDelete(String shortCode, String ownerId) {
        boolean deleted = postgresRepository.softDelete(shortCode, ownerId);

        if (deleted) {
            try {
                redisTemplate.delete(KEY_PREFIX + shortCode);
            } catch (DataAccessException e) {
                log.warning("Redis ошибка инвалидации: " + e.getMessage());
            }
        }

        return deleted;
    }

    private Map<String, String> toHash(ShortUrl url) {
        return Map.of(
                "originalUrl", url.originalUrl(),
                "ownerId", url.ownerId(),
                "createdAt", url.createdAt().toString(),
                "expiresAt", url.expiresAt().toString(),
                "isDeleted", Boolean.toString(url.isDeleted())
        );
    }

    private ShortUrl fromHash(
            String shortCode,
            Map<Object, Object> hash
    ) {
        return new ShortUrl(
                shortCode,
                (String) hash.get("originalUrl"),
                (String) hash.get("ownerId"),
                Instant.parse((String) hash.get("createdAt")),
                Instant.parse((String) hash.get("expiresAt")),
                Boolean.parseBoolean((String) hash.get("isDeleted"))
        );
    }
}
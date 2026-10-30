package com.example.url_shortener.repository;

import com.example.url_shortener.model.IdempotencyKey;
import com.example.url_shortener.model.IdempotencyRecord;
import com.example.url_shortener.model.ShortUrl;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

@Repository
@Profile("postgres")
public class PostgresIdempotencyRepository implements IdempotencyStore {
    private final JdbcTemplate jdbcTemplate;

    public PostgresIdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public IdempotencyRecord getOrCreate(
            IdempotencyKey key,
            String originalUrl,
            Duration ttl,
            Supplier<ShortUrl> creator
    ) {
        String insertSql = """
            INSERT INTO idempotency_records (
                owner_id,
                idempotency_key,
                original_url,
                ttl_seconds
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT (owner_id, idempotency_key) DO NOTHING
            """;

        int inserted = jdbcTemplate.update(
                insertSql,
                key.ownerId(),
                key.key(),
                originalUrl,
                ttl.getSeconds()
        );

        if (inserted == 1) {
            ShortUrl url = creator.get();

            String updateSql = """
                UPDATE idempotency_records
                SET short_url = ?
                WHERE owner_id = ?
                  AND idempotency_key = ?
                """;

            jdbcTemplate.update(
                    updateSql,
                    url.shortCode(),
                    key.ownerId(),
                    key.key()
            );

            return new IdempotencyRecord(originalUrl, ttl, url);
        }

        String selectSql = """
            SELECT
                i.original_url AS request_original_url,
                i.ttl_seconds,
                u.short_url,
                u.original_url,
                u.owner_id,
                u.created_at,
                u.expires_at,
                u.is_deleted
            FROM idempotency_records i
            JOIN short_urls u ON u.short_url = i.short_url
            WHERE i.owner_id = ?
              AND i.idempotency_key = ?
            """;

        return jdbcTemplate.query(
                selectSql,
                (rs, rowNum) -> {
                    ShortUrl url = new ShortUrl(
                            rs.getString("short_url"),
                            rs.getString("original_url"),
                            rs.getString("owner_id"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            rs.getBoolean("is_deleted")
                    );

                    return new IdempotencyRecord(
                            rs.getString("request_original_url"),
                            Duration.ofSeconds(rs.getLong("ttl_seconds")),
                            url
                    );
                },
                key.ownerId(),
                key.key()
        ).stream().findFirst().orElseThrow(
                () -> new IllegalStateException(
                        "Idempotency record not found after conflict"
                )
        );
    }
}
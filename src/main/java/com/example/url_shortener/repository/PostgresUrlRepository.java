package com.example.url_shortener.repository;

import com.example.url_shortener.model.ShortUrl;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@Profile("postgres")
public class PostgresUrlRepository implements UrlRepository {
    private final JdbcTemplate jdbcTemplate;

    public PostgresUrlRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean saveIfAbsent(ShortUrl url) {
        String sql = """
            INSERT INTO short_urls (
                short_url,
                original_url,
                owner_id,
                created_at,
                expires_at,
                is_deleted
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (short_url) DO NOTHING
            """;

        int affectedRows = jdbcTemplate.update(
                sql,
                url.shortCode(),
                url.originalUrl(),
                url.ownerId(),
                url.createdAt().atOffset(ZoneOffset.UTC),
                url.expiresAt().atOffset(ZoneOffset.UTC),
                url.isDeleted()
        );

        return affectedRows == 1;
    }

    @Override
    public Optional<ShortUrl> findByCode(String shortCode) {
        String sql = """
        SELECT
            short_url,
            original_url,
            owner_id,
            created_at,
            expires_at,
            is_deleted
        FROM short_urls
        WHERE short_url = ?
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) ->
                        new ShortUrl(
                                rs.getString("short_url"),
                                rs.getString("original_url"),
                                rs.getString("owner_id"),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                rs.getBoolean("is_deleted")
                        ),
                shortCode
        ).stream().findFirst();
    }

    @Override
    public boolean softDelete(String shortCode, String ownerId) {
        String sql = """
        UPDATE short_urls
        SET is_deleted = TRUE
        WHERE short_url = ?
          AND owner_id = ?
          AND is_deleted = FALSE
        """;

        int affectedRows = jdbcTemplate.update(
                sql,
                shortCode,
                ownerId
        );

        return affectedRows == 1;
    }
}
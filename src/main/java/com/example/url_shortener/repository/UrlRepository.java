package com.example.url_shortener.repository;

import com.example.url_shortener.model.ShortUrl;

import java.util.Optional;

public interface UrlRepository {
    boolean saveIfAbsent(ShortUrl url);

    Optional<ShortUrl> findByCode(String shortCode);

    boolean softDelete(String shortCode, String ownerId);
}

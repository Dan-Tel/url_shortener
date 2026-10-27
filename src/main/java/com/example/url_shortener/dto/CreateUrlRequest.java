package com.example.url_shortener.dto;

public record CreateUrlRequest(String originalUrl, int ttlHours) {
}

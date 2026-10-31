package com.example.url_shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUrlRequest(
        @NotBlank
        @Size(max = 2048)
        String originalUrl,

        int ttlHours
) {
}

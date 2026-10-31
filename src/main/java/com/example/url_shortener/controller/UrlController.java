package com.example.url_shortener.controller;

import com.example.url_shortener.dto.CreateUrlRequest;
import com.example.url_shortener.dto.CreateUrlResponse;
import com.example.url_shortener.dto.ResolveUrlResponse;
import com.example.url_shortener.model.ShortUrl;
import com.example.url_shortener.service.UrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("api/v1/urls")
public class UrlController {
    private final UrlService service;

    public UrlController(UrlService service) {
        this.service = service;
    }

    @GetMapping("/{shortCode}")
    public ResolveUrlResponse resolve(@PathVariable String shortCode) {
        String originalUrl = service.resolveUrl(shortCode);
        return new ResolveUrlResponse(originalUrl);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUrlResponse create(
            @Valid @RequestBody CreateUrlRequest request,
            @NotBlank @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ShortUrl shortUrl = service.createIdempotentUrl(
                request.originalUrl(),
                "test-user",
                Duration.ofHours(request.ttlHours()),
                idempotencyKey
        );

        return new CreateUrlResponse(
                shortUrl.shortCode(),
                shortUrl.originalUrl(),
                shortUrl.expiresAt()
        );
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> delete(@PathVariable String shortCode) {
        boolean deleted = service.deleteUrl(shortCode, "test-user");

        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}

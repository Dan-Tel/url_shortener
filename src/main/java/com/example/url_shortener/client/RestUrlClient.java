
package com.example.url_shortener.client;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

public class RestUrlClient implements AutoCloseable {
    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private final HttpClient client;
    private final String baseUrl;

    public RestUrlClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");

        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build();
    }

    // READ
    public HttpResponse<String> resolve(String shortCode)
            throws IOException, InterruptedException {

        return execute(
                "GET",
                "/api/v1/urls/" + shortCode,
                null,
                null
        );
    }

    // CREATE
    public HttpResponse<String> create(
            String originalUrl,
            int ttlHours,
            String idempotencyKey
    ) throws IOException, InterruptedException {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key is required"
            );
        }

        // Serialize once; reuse exactly the same body on retries.
        String body = JSON.writeValueAsString(Map.of(
                "originalUrl", originalUrl,
                "ttlHours", ttlHours
        ));

        return execute(
                "POST",
                "/api/v1/urls",
                body,
                idempotencyKey
        );
    }

    // DELETE
    public HttpResponse<String> delete(String shortCode)
            throws IOException, InterruptedException {

        return execute(
                "DELETE",
                "/api/v1/urls/" + shortCode,
                null,
                null
        );
    }

    private HttpResponse<String> execute(
            String method,
            String path,
            String body,
            String idempotencyKey
    ) throws IOException, InterruptedException {

        long deadline = RetryPolicy.newDeadline();

        for (int attempt = 1;
             attempt <= RetryPolicy.MAX_ATTEMPTS;
             attempt++) {

            long timeout = RetryPolicy.remainingTimeout(deadline);

            if (timeout <= 0) {
                throw new HttpTimeoutException(
                        "Overall REST deadline exceeded"
                );
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofNanos(timeout));

            switch (method) {
                case "GET" -> builder.GET();

                case "DELETE" -> builder.DELETE();

                case "POST" -> builder
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", idempotencyKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body));

                default -> throw new IllegalArgumentException(
                        "Unsupported method: " + method
                );
            }

            try {
                HttpResponse<String> response = client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                int status = response.statusCode();

                boolean retryable =
                        status == 502 ||
                                status == 503 ||
                                status == 504;

                if (!retryable ||
                        attempt == RetryPolicy.MAX_ATTEMPTS) {
                    return response;
                }

            } catch (IOException e) {
                // Temporary network failure or request timeout.
                if (attempt == RetryPolicy.MAX_ATTEMPTS) {
                    throw e;
                }
            }

            RetryPolicy.backoff(attempt, deadline);
        }

        throw new IllegalStateException("Unreachable");
    }

    @Override
    public void close() {
        client.close();
    }
}

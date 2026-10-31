
package com.example.url_shortener.client;

import com.example.url_shortener.grpc.generated.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

public class PayloadSizeMeasurement {

    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void print(String operation, int rest, int grpc) {
        System.out.printf(
                "%-22s | %10d | %10d%n",
                operation, rest, grpc
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static void main(String[] args) throws Exception {

        String id = UUID.randomUUID().toString();
        String originalUrl =
                "https://example.com/benchmark/payload/" + id;

        int ttlHours = 24;

        String restKey = "rest-" + id;
        String grpcKey = "grpc-" + id;

        try (
                var rest = new RestUrlClient(
                        "http://127.0.0.1:8080"
                );
                var grpc = new GrpcUrlClient(
                        "127.0.0.1", 9090
                )
        ) {
            // ---------- CREATE ----------

            String restCreateBody = JSON.writeValueAsString(
                    Map.of(
                            "originalUrl", originalUrl,
                            "ttlHours", ttlHours
                    )
            );

            var grpcCreateRequest = CreateUrlRequest.newBuilder()
                    .setOriginalUrl(originalUrl)
                    .setTtlHours(ttlHours)
                    .setIdempotencyKey(grpcKey)
                    .build();

            var restCreated = rest.create(
                    originalUrl, ttlHours, restKey
            );

            var grpcCreated = grpc.create(
                    originalUrl, ttlHours, grpcKey
            );

            require(restCreated.statusCode() == 201,
                    "REST CREATE failed");

            String restCode = JSON.readTree(restCreated.body())
                    .path("shortCode")
                    .asText();

            String grpcCode = grpcCreated.getShortCode();

            require(!restCode.isBlank() && !grpcCode.isBlank(),
                    "CREATE did not return shortCode");

            // ---------- READ ----------

            var grpcReadRequest = ResolveUrlRequest.newBuilder()
                    .setShortCode(grpcCode)
                    .build();

            var restResolved = rest.resolve(restCode);
            var grpcResolved = grpc.resolve(grpcCode);

            require(restResolved.statusCode() == 200,
                    "REST READ failed");

            // ---------- DELETE ----------

            var grpcDeleteRequest = DeleteUrlRequest.newBuilder()
                    .setShortCode(grpcCode)
                    .build();

            var restDeleted = rest.delete(restCode);
            var grpcDeleted = grpc.delete(grpcCode);

            require(restDeleted.statusCode() == 204,
                    "REST DELETE failed");

            // ---------- RESULTS ----------

            System.out.println();
            System.out.println("PAYLOAD SIZE COMPARISON (bytes)");
            System.out.println(
                    "Operation              |  REST/JSON | gRPC/Proto"
            );
            System.out.println(
                    "------------------------------------------------"
            );

            print("CREATE request",
                    bytes(restCreateBody),
                    grpcCreateRequest.getSerializedSize());

            print("CREATE response",
                    bytes(restCreated.body()),
                    grpcCreated.getSerializedSize());

            print("READ request body",
                    0,
                    grpcReadRequest.getSerializedSize());

            print("READ response",
                    bytes(restResolved.body()),
                    grpcResolved.getSerializedSize());

            print("DELETE request body",
                    0,
                    grpcDeleteRequest.getSerializedSize());

            print("DELETE response",
                    bytes(restDeleted.body()),
                    grpcDeleted.getSerializedSize());
        }
    }
}

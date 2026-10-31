
package com.example.url_shortener.client;

import com.example.url_shortener.grpc.generated.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ClientDemo {
    private static final JsonMapper JSON =
            JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        System.out.println("=== REAL SPRING BOOT ===");
        realRestDemo();
        realGrpcDemo();

        System.out.println("\n=== RETRY AND TIMEOUT ===");
        failureDemo();

        System.out.println("\nALL DEMO CASES PASSED");
    }

    private static void verify(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("[FAIL] " + message);
        }
        System.out.println("[PASS] " + message);
    }

    private static void realRestDemo() throws Exception {
        System.out.println("\n--- REST ---");

        try (var client = new RestUrlClient(
                "http://127.0.0.1:8080")) {

            String id = UUID.randomUUID().toString();
            String url = "https://example.com/rest/" + id;
            String key = "demo-rest-" + id;

            var created = client.create(url, 24, key);
            verify(created.statusCode() == 201, "CREATE 201");

            String code = JSON.readTree(created.body())
                    .path("shortCode").asText();
            verify(!code.isBlank(), "shortCode returned");

            var repeated = client.create(url, 24, key);
            String repeatedCode = JSON.readTree(repeated.body())
                    .path("shortCode").asText();

            verify(repeated.statusCode() == 201
                            && code.equals(repeatedCode),
                    "Same key returns same shortCode");

            var conflict = client.create(url + "/changed", 24, key);
            verify(conflict.statusCode() == 409,
                    "Different payload with same key -> 409");

            var resolved = client.resolve(code);
            verify(resolved.statusCode() == 200
                    && resolved.body().contains(url), "READ 200");

            var deleted = client.delete(code);
            verify(deleted.statusCode() == 204, "DELETE 204");

            verify(client.resolve(code).statusCode() == 404,
                    "READ after DELETE -> 404");
        }
    }

    private static void realGrpcDemo() {
        System.out.println("\n--- gRPC ---");

        try (var client = new GrpcUrlClient("127.0.0.1", 9090)) {
            String id = UUID.randomUUID().toString();
            String url = "https://example.com/grpc/" + id;
            String key = "demo-grpc-" + id;

            var created = client.create(url, 24, key);
            String code = created.getShortCode();

            verify(!code.isBlank(), "CREATE returned shortCode");

            var repeated = client.create(url, 24, key);
            verify(code.equals(repeated.getShortCode()),
                    "Same key returns same shortCode");

            try {
                client.create(url + "/changed", 24, key);
                throw new IllegalStateException("Expected ALREADY_EXISTS");
            } catch (StatusRuntimeException e) {
                verify(
                        e.getStatus().getCode()
                                == Status.Code.ALREADY_EXISTS,
                        "Different payload -> ALREADY_EXISTS"
                );
            }

            verify(
                    client.resolve(code).getOriginalUrl().equals(url),
                    "READ successful"
            );

            client.delete(code);
            System.out.println("[PASS] DELETE successful");

            try {
                client.resolve(code);
                throw new IllegalStateException("Expected NOT_FOUND");
            } catch (StatusRuntimeException e) {
                verify(
                        e.getStatus().getCode()
                                == Status.Code.NOT_FOUND,
                        "READ after DELETE -> NOT_FOUND"
                );
            }
        }
    }

    private static void failureDemo() throws Exception {
        // Each operation fails twice, then succeeds.
        var restRead = new AtomicInteger();
        var restCreate = new AtomicInteger();
        var restDelete = new AtomicInteger();

        var grpcRead = new AtomicInteger();
        var grpcCreate = new AtomicInteger();
        var grpcDelete = new AtomicInteger();

        var restKey = new AtomicReference<String>();
        var grpcKey = new AtomicReference<String>();

        var httpExecutor = Executors.newCachedThreadPool();
        var scheduler = Executors.newScheduledThreadPool(2);

        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );
        httpServer.setExecutor(httpExecutor);

        httpServer.createContext("/api/v1/urls", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (method.equals("GET")
                    && path.endsWith("/slow")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, 200,
                        "{\"originalUrl\":\"https://example.com\"}");
                return;
            }

            if (method.equals("GET")
                    && path.endsWith("/flaky")) {
                int status = restRead.incrementAndGet() < 3
                        ? 503 : 200;
                reply(exchange, status,
                        "{\"originalUrl\":\"https://example.com\"}");
                return;
            }

            if (method.equals("POST")
                    && path.equals("/api/v1/urls")) {
                String key = exchange.getRequestHeaders()
                        .getFirst("Idempotency-Key");

                if (restKey.get() == null) {
                    restKey.set(key);
                } else if (!Objects.equals(restKey.get(), key)) {
                    reply(exchange, 400, "{}");
                    return;
                }

                int status = restCreate.incrementAndGet() < 3
                        ? 503 : 201;
                reply(exchange, status,
                        "{\"shortCode\":\"MOCK1234\"}");
                return;
            }

            if (method.equals("DELETE")
                    && path.endsWith("/flaky")) {
                int status = restDelete.incrementAndGet() < 3
                        ? 503 : 204;
                reply(exchange, status, "");
                return;
            }

            reply(exchange, 404, "{}");
        });

        httpServer.start();

        var grpcService = new UrlShortenerServiceGrpc
                .UrlShortenerServiceImplBase() {

            @Override
            public void resolveUrl(
                    ResolveUrlRequest request,
                    StreamObserver<ResolveUrlResponse> observer
            ) {
                if (request.getShortCode().equals("slow")) {
                    scheduler.schedule(() -> {
                        try {
                            observer.onNext(
                                    ResolveUrlResponse.newBuilder()
                                            .setOriginalUrl(
                                                    "https://example.com")
                                            .build()
                            );
                            observer.onCompleted();
                        } catch (RuntimeException ignored) {
                            // The client may have cancelled the RPC.
                        }
                    }, 3, TimeUnit.SECONDS);
                    return;
                }

                if (grpcRead.incrementAndGet() < 3) {
                    observer.onError(
                            Status.UNAVAILABLE.asRuntimeException());
                    return;
                }

                observer.onNext(ResolveUrlResponse.newBuilder()
                        .setOriginalUrl("https://example.com")
                        .build());
                observer.onCompleted();
            }

            @Override
            public void createUrl(
                    CreateUrlRequest request,
                    StreamObserver<CreateUrlResponse> observer
            ) {
                if (grpcKey.get() == null) {
                    grpcKey.set(request.getIdempotencyKey());
                } else if (!grpcKey.get()
                        .equals(request.getIdempotencyKey())) {
                    observer.onError(
                            Status.INVALID_ARGUMENT.asRuntimeException());
                    return;
                }

                if (grpcCreate.incrementAndGet() < 3) {
                    observer.onError(
                            Status.UNAVAILABLE.asRuntimeException());
                    return;
                }

                observer.onNext(CreateUrlResponse.newBuilder()
                        .setShortCode("MOCK5678")
                        .build());
                observer.onCompleted();
            }

            @Override
            public void deleteUrl(
                    DeleteUrlRequest request,
                    StreamObserver<DeleteUrlResponse> observer
            ) {
                if (grpcDelete.incrementAndGet() < 3) {
                    observer.onError(
                            Status.UNAVAILABLE.asRuntimeException());
                    return;
                }

                observer.onNext(DeleteUrlResponse.newBuilder().build());
                observer.onCompleted();
            }
        };

        var grpcServer = ServerBuilder.forPort(0)
                .addService(grpcService)
                .build()
                .start();

        try (
                var rest = new RestUrlClient(
                        "http://127.0.0.1:"
                                + httpServer.getAddress().getPort());
                var grpc = new GrpcUrlClient(
                        "127.0.0.1", grpcServer.getPort())
        ) {
            System.out.println("\n--- REST retries ---");

            verify(rest.resolve("flaky").statusCode() == 200
                            && restRead.get() == 3,
                    "GET: 503, 503, 200");

            verify(rest.create(
                            "https://example.com", 24, "same-rest-key"
                    ).statusCode() == 201
                            && restCreate.get() == 3
                            && "same-rest-key".equals(restKey.get()),
                    "CREATE: 503, 503, 201; key preserved");

            verify(rest.delete("flaky").statusCode() == 204
                            && restDelete.get() == 3,
                    "DELETE: 503, 503, 204");

            System.out.println("\n--- gRPC retries ---");

            verify(grpc.resolve("flaky").getOriginalUrl()
                            .equals("https://example.com")
                            && grpcRead.get() == 3,
                    "GET: UNAVAILABLE x2, then OK");

            verify(grpc.create(
                            "https://example.com", 24, "same-grpc-key"
                    ).getShortCode().equals("MOCK5678")
                            && grpcCreate.get() == 3
                            && "same-grpc-key".equals(grpcKey.get()),
                    "CREATE: UNAVAILABLE x2, then OK; key preserved");

            grpc.delete("flaky");
            verify(grpcDelete.get() == 3,
                    "DELETE: UNAVAILABLE x2, then OK");

            System.out.println("\n--- Timeouts ---");

            try {
                rest.resolve("slow");
                throw new IllegalStateException("REST timeout expected");
            } catch (HttpTimeoutException e) {
                System.out.println("[PASS] REST timeout enforced");
            }

            try {
                grpc.resolve("slow");
                throw new IllegalStateException("gRPC deadline expected");
            } catch (StatusRuntimeException e) {
                verify(
                        e.getStatus().getCode()
                                == Status.Code.DEADLINE_EXCEEDED,
                        "gRPC deadline enforced"
                );
            }

        } finally {
            grpcServer.shutdownNow();
            httpServer.stop(0);
            scheduler.shutdownNow();
            httpExecutor.shutdownNow();
        }
    }

    private static void reply(
            HttpExchange exchange,
            int status,
            String body
    ) {
        try {
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");

            exchange.sendResponseHeaders(status, bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // Expected if a timed-out client closed its connection.
        }
    }
}


package com.example.url_shortener.client;

import com.example.url_shortener.grpc.generated.*;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class GrpcUrlClient implements AutoCloseable {
    private final ManagedChannel channel;

    private final UrlShortenerServiceGrpc
            .UrlShortenerServiceBlockingStub stub;

    public GrpcUrlClient(String host, int port) {

        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .disableRetry()
                .build();

        this.stub = UrlShortenerServiceGrpc
                .newBlockingStub(channel);
    }

    // READ
    public ResolveUrlResponse resolve(String shortCode) {

        ResolveUrlRequest request = ResolveUrlRequest.newBuilder()
                .setShortCode(shortCode)
                .build();

        return executeWithRetry(
                s -> s.resolveUrl(request)
        );
    }

    // CREATE
    public CreateUrlResponse create(
            String originalUrl,
            int ttlHours,
            String idempotencyKey
    ) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key is required"
            );
        }

        // The same request is reused for every attempt.
        CreateUrlRequest request = CreateUrlRequest.newBuilder()
                .setOriginalUrl(originalUrl)
                .setTtlHours(ttlHours)
                .setIdempotencyKey(idempotencyKey)
                .build();

        return executeWithRetry(
                s -> s.createUrl(request)
        );
    }

    // DELETE
    public DeleteUrlResponse delete(String shortCode) {

        DeleteUrlRequest request = DeleteUrlRequest.newBuilder()
                .setShortCode(shortCode)
                .build();

        return executeWithRetry(
                s -> s.deleteUrl(request)
        );
    }

    private <T> T executeWithRetry(
            Function<
                    UrlShortenerServiceGrpc.UrlShortenerServiceBlockingStub,
                    T
                    > operation
    ) {

        long deadline = RetryPolicy.newDeadline();

        for (int attempt = 1;
             attempt <= RetryPolicy.MAX_ATTEMPTS;
             attempt++) {

            long timeout = RetryPolicy.remainingTimeout(deadline);

            if (timeout <= 0) {
                throw Status.DEADLINE_EXCEEDED
                        .withDescription("Overall gRPC deadline exceeded")
                        .asRuntimeException();
            }

            var attemptStub = stub.withDeadlineAfter(
                    timeout,
                    TimeUnit.NANOSECONDS
            );

            try {
                return operation.apply(attemptStub);

            } catch (StatusRuntimeException e) {

                Status.Code code = e.getStatus().getCode();

                boolean retryable =
                        code == Status.Code.UNAVAILABLE ||
                                code == Status.Code.DEADLINE_EXCEEDED;

                if (!retryable ||
                        attempt == RetryPolicy.MAX_ATTEMPTS) {
                    throw e;
                }

                try {
                    RetryPolicy.backoff(attempt, deadline);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();

                    throw Status.CANCELLED
                            .withCause(e1)
                            .asRuntimeException();
                }
            }
        }

        throw new IllegalStateException("Unreachable");
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}

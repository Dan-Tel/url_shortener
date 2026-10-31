package com.example.url_shortener.grpc;

import com.example.url_shortener.exception.IdempotencyConflictException;
import com.example.url_shortener.model.ShortUrl;
import com.example.url_shortener.service.UrlService;
import com.example.url_shortener.grpc.generated.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.validation.ConstraintViolationException;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Duration;
import java.util.NoSuchElementException;

@GrpcService
public class UrlGrpcService extends UrlShortenerServiceGrpc.UrlShortenerServiceImplBase {
    private final UrlService service;

    public UrlGrpcService(UrlService service) {
        this.service = service;
    }

    @Override
    public void resolveUrl(ResolveUrlRequest request, StreamObserver<ResolveUrlResponse> responseObserver) {
        try {
            String shortCode = request.getShortCode();
            String originalUrl = service.resolveUrl(shortCode);

            ResolveUrlResponse response = ResolveUrlResponse.newBuilder().setOriginalUrl(originalUrl).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (NoSuchElementException ex) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("URL не был найден").asRuntimeException());
        }
    }

    @Override
    public void createUrl(CreateUrlRequest request, StreamObserver<CreateUrlResponse> responseObserver) {
        try {
            ShortUrl shortUrl = service.createIdempotentUrl(request.getOriginalUrl(), "test-user", Duration.ofHours(request.getTtlHours()), request.getIdempotencyKey());
            CreateUrlResponse response = CreateUrlResponse.newBuilder().setShortCode(shortUrl.shortCode()).setOriginalUrl(shortUrl.originalUrl()).setExpiresAt(shortUrl.expiresAt().toString()).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (ConstraintViolationException | IllegalArgumentException ex) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription(ex.getMessage())
                            .asRuntimeException()
            );
        } catch (IdempotencyConflictException ex) {
            responseObserver.onError(
                    Status.ALREADY_EXISTS
                            .withDescription(ex.getMessage())
                            .asRuntimeException()
            );
        }
    }

    @Override
    public void deleteUrl(DeleteUrlRequest request, StreamObserver<DeleteUrlResponse> responseObserver) {
        boolean deleted = service.deleteUrl(request.getShortCode(), "test-user");

        if (deleted) {
            responseObserver.onNext(DeleteUrlResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
        else {
            responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
        }
    }
}


import grpc from 'k6/net/grpc';
import { config } from '../config.js';

const client = new grpc.Client();

if (config.protocol === 'grpc') {
    client.load(
        ['../../src/main/proto'],
        'url_shortener.proto'
    );
}

let connected = false;

function ensureConnected() {
    if (!connected) {
        client.connect(config.grpcAddress, {
            plaintext: __ENV.GRPC_TLS !== 'true',
            timeout: '2s',
        });

        connected = true;
    }
}

// READ
export function resolveGrpcUrl(shortCode) {
    ensureConnected();

    return client.invoke(
        'UrlShortenerService/ResolveUrl',
        {
            short_code: shortCode,
        },
        {
            timeout: '2s',
            tags: {
                protocol: 'grpc',
                operation: 'read',
            },
        }
    );
}

// CREATE
export function createGrpcUrl(originalUrl, ttlHours, idempotencyKey) {
    ensureConnected();

    return client.invoke(
        'UrlShortenerService/CreateUrl',
        {
            original_url: originalUrl,
            ttl_hours: ttlHours,
            idempotency_key: idempotencyKey,
        },
        {
            timeout: '2s',
            tags: {
                protocol: 'grpc',
                operation: 'create',
            },
        }
    );
}

// DELETE
export function deleteGrpcUrl(shortCode) {
    ensureConnected();

    return client.invoke(
        'UrlShortenerService/DeleteUrl',
        {
            short_code: shortCode,
        },
        {
            timeout: '2s',
            tags: {
                protocol: 'grpc',
                operation: 'delete',
            },
        }
    );
}

export const grpcStatusOK = grpc.StatusOK;

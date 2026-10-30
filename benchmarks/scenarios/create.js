
import { check } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';

import { config } from '../config.js';
import {
    createGrpcUrl,
    grpcStatusOK,
} from '../clients/grpc.js';

export const options = {
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(95)', 'p(99)'
    ],

    scenarios: {
        create: {
            executor: 'constant-arrival-rate',
            rate: config.rps,
            timeUnit: '1s',
            duration: config.duration,
            preAllocatedVUs: 30,
            maxVUs: 100,
        },
    },

    thresholds: {
        checks: ['rate==1'],
        dropped_iterations: ['count==0'],
    },
};

export default function () {
    const id =
        `${exec.scenario.startTime}-${exec.scenario.iterationInTest}`;

    const originalUrl =
        `https://example.com/benchmark/create/${id}`;

    const idempotencyKey = `k6-create-${id}`;

    // gRPC CREATE
    if (config.protocol === 'grpc') {
        const response = createGrpcUrl(
            originalUrl,
            24,
            idempotencyKey
        );

        check(response, {
            'gRPC CREATE status OK': (r) =>
                r !== null && r.status === grpcStatusOK,

            'gRPC CREATE returned shortCode': (r) =>
                r !== null &&
                r.status === grpcStatusOK &&
                Boolean(r.message?.shortCode),
        });

        return;
    }

    // REST CREATE
    if (config.protocol === 'rest') {
        const response = http.post(
            `${config.baseUrl}/api/v1/urls`,
            JSON.stringify({
                originalUrl,
                ttlHours: 24,
            }),
            {
                headers: {
                    'Content-Type': 'application/json',
                    'Idempotency-Key': idempotencyKey,
                },
                timeout: '2s',
                tags: {
                    protocol: 'rest',
                    operation: 'create',
                    name: 'POST /api/v1/urls',
                },
            }
        );

        check(response, {
            'REST CREATE status 201': (r) =>
                r.status === 201,

            'REST CREATE returned shortCode': (r) =>
                r.status === 201 &&
                Boolean(r.json('shortCode')),
        });

        return;
    }

    throw new Error(`Unknown protocol: ${config.protocol}`);
}

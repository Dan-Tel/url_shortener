
import { check } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

import { config } from '../config.js';
import {
    deleteGrpcUrl,
    grpcStatusOK,
} from '../clients/grpc.js';

const urls = new SharedArray('delete-urls', () => {
    const data = JSON.parse(open('../data/delete-urls.json'));

    if (!Array.isArray(data) || data.length === 0) {
        throw new Error('delete-urls.json must not be empty');
    }

    return data;
});

export const options = {
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(95)', 'p(99)'
    ],

    scenarios: {
        delete: {
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
    const index = exec.scenario.iterationInTest;

    if (index >= urls.length) {
        exec.test.abort('Not enough URLs for DELETE test');
        return;
    }

    const shortCode = urls[index].shortCode;

    // gRPC DELETE
    if (config.protocol === 'grpc') {
        const response = deleteGrpcUrl(shortCode);

        check(response, {
            'gRPC DELETE status OK': (r) =>
                r !== null && r.status === grpcStatusOK,
        });

        return;
    }

    // REST DELETE
    if (config.protocol === 'rest') {
        const response = http.del(
            `${config.baseUrl}/api/v1/urls/${shortCode}`,
            null,
            {
                timeout: '2s',
                tags: {
                    protocol: 'rest',
                    operation: 'delete',
                    name: 'DELETE /api/v1/urls/:shortCode',
                },
            }
        );

        check(response, {
            'REST DELETE status 204': (r) =>
                r.status === 204,
        });

        return;
    }

    throw new Error(`Unknown protocol: ${config.protocol}`);
}

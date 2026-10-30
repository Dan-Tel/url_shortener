
import { check } from 'k6';
import { SharedArray } from 'k6/data';

import { config } from '../config.js';
import { resolveUrl } from '../clients/rest.js';
import {
    resolveGrpcUrl,
    grpcStatusOK,
} from '../clients/grpc.js';

// Load test data once and share it across VUs.
// Hot mode does not require the JSON file.
const urls = config.readMode === 'random'
    ? new SharedArray('read-urls', () => {
        const data = JSON.parse(open('../data/read-urls.json'));

        if (!Array.isArray(data) || data.length === 0) {
            throw new Error('read-urls.json must contain a non-empty array');
        }

        return data;
    })
    : null;

export const options = {
    summaryTrendStats: [
        'avg',
        'min',
        'med',
        'max',
        'p(95)',
        'p(99)',
    ],

    scenarios: {
        read: {
            executor: 'constant-arrival-rate',
            rate: config.rps,
            timeUnit: '1s',
            duration: config.duration,
            preAllocatedVUs: 30,
            maxVUs: 100,
        },
    },
};

// Select the short code for this iteration
function getShortCode() {
    if (config.readMode === 'hot') {
        if (!config.shortCode) {
            throw new Error('SHORT_CODE is required in hot mode');
        }

        return config.shortCode;
    }

    if (config.readMode === 'random') {
        const index = Math.floor(Math.random() * urls.length);
        return urls[index].shortCode;
    }

    throw new Error(`Unknown READ_MODE: ${config.readMode}`);
}

export default function () {
    const shortCode = getShortCode();

    if (config.protocol === 'rest') {
        const response = resolveUrl(shortCode);

        check(response, {
            'REST status 200': (r) => r.status === 200,
        });

        return;
    }

    if (config.protocol === 'grpc') {
        const response = resolveGrpcUrl(shortCode);

        check(response, {
            'gRPC status OK': (r) =>
                r !== null && r.status === grpcStatusOK,
        });

        return;
    }

    throw new Error(`Unknown protocol: ${config.protocol}`);
}

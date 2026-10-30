
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Configuration
const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080';
const COUNT = Number(process.env.COUNT || 1000);
const CONCURRENCY = 10;

// Unique identifier for each seed run
const runId = `${Date.now()}-${process.pid}`;

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const outputFile = process.env.OUTPUT_FILE || 'read-urls.json';
const outputPath = path.resolve(currentDir, '../data', outputFile);

const urls = new Array(COUNT);
let nextIndex = 0;

// Create one short URL through our REST API
async function createUrl(index) {
    const originalUrl =
        `https://example.com/benchmark/${runId}/${index}`;

    const response = await fetch(`${BASE_URL}/api/v1/urls`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': `seed-${runId}-${index}`,
        },
        body: JSON.stringify({
            originalUrl,
            ttlHours: 168, // 7 days
        }),
        signal: AbortSignal.timeout(10000),
    });

    if (response.status !== 201) {
        const body = await response.text();
        throw new Error(
            `Request ${index} failed: HTTP ${response.status}: ${body}`
        );
    }

    const data = await response.json();

    if (!data.shortCode) {
        throw new Error(`Request ${index}: missing shortCode`);
    }

    return {
        shortCode: data.shortCode,
        originalUrl,
    };
}

// Each worker creates URLs until all tasks are completed
async function worker() {
    while (nextIndex < COUNT) {
        const index = nextIndex++;
        urls[index] = await createUrl(index);
    }
}

// Run at most 10 concurrent workers
await Promise.all(
    Array.from(
        { length: Math.min(CONCURRENCY, COUNT) },
        () => worker()
    )
);

// Save the generated dataset
await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(
    outputPath,
    JSON.stringify(urls, null, 2)
);

console.log(`Created ${urls.length} URLs`);
console.log(`Saved to ${outputPath}`);

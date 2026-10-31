
package com.example.url_shortener.client;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

final class RetryPolicy {

    static final int MAX_ATTEMPTS = 3;

    private static final long PER_ATTEMPT_TIMEOUT =
            TimeUnit.SECONDS.toNanos(2);

    private static final long OVERALL_TIMEOUT =
            TimeUnit.SECONDS.toNanos(5);

    private RetryPolicy() {}

    static long newDeadline() {
        return System.nanoTime() + OVERALL_TIMEOUT;
    }

    static long remainingTimeout(long deadline) {
        long remaining = deadline - System.nanoTime();

        return Math.max(
                0,
                Math.min(remaining, PER_ATTEMPT_TIMEOUT)
        );
    }

    static void backoff(int failedAttempt, long deadline)
            throws InterruptedException {

        // Exponential backoff: 200ms, 400ms.
        long baseDelayMs = 200L << (failedAttempt - 1);

        // Equal jitter: [baseDelay/2, baseDelay].
        long jitteredDelayMs = ThreadLocalRandom.current()
                .nextLong(baseDelayMs / 2, baseDelayMs + 1);

        // Never sleep longer than the remaining overall deadline.
        long remainingNanos = deadline - System.nanoTime();

        if (remainingNanos <= 0) {
            return;
        }

        long sleepNanos = Math.min(
                TimeUnit.MILLISECONDS.toNanos(jitteredDelayMs),
                remainingNanos
        );

        System.out.printf(
                "[RETRY] Failed attempt %d | base=%dms | jitter=%dms%n",
                failedAttempt,
                baseDelayMs,
                TimeUnit.NANOSECONDS.toMillis(sleepNanos)
        );

        TimeUnit.NANOSECONDS.sleep(sleepNanos);
    }
}

/*
 * Bounded Selenium headless sessions with acquire timeout and circuit breaker (TS-03, TS-14).
 *
 * Callers: HttpFetcher only.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/**
 * Limits concurrent WebDriver instances; opens a short circuit after repeated acquire timeouts (TS-03).
 */
final class HeadlessSessionPool {

    private final RuntimeConfig config;
    private final Clock clock;
    private final Semaphore permits;
    private final AtomicInteger consecutiveAcquireFailures = new AtomicInteger();
    private volatile Instant circuitOpenUntil = Instant.EPOCH;

    HeadlessSessionPool(RuntimeConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.permits = new Semaphore(config.fetchMaxHeadlessSessions(), true);
    }

    /**
     * Tries to take a slot within {@link RuntimeConfig#fetchHeadlessAcquireTimeoutMs()}.
     *
     * @return true if a slot was acquired
     */
    boolean tryAcquireSlot() throws InterruptedException {
        if (clock.instant().isBefore(circuitOpenUntil)) {
            return false;
        }
        boolean ok =
                permits.tryAcquire(
                        config.fetchHeadlessAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        if (ok) {
            consecutiveAcquireFailures.set(0);
            return true;
        }
        int n = consecutiveAcquireFailures.incrementAndGet();
        if (n >= config.fetchHeadlessCircuitOpenThreshold()) {
            circuitOpenUntil =
                    clock.instant().plus(Duration.ofMinutes(1));
            consecutiveAcquireFailures.set(0);
        }
        return false;
    }

    void releaseSlot() {
        permits.release();
    }

    boolean isCircuitOpen() {
        return clock.instant().isBefore(circuitOpenUntil);
    }
}

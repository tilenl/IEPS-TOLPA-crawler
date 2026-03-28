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
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

/**
 * Limits concurrent WebDriver instances; opens a short circuit after repeated acquire timeouts (TS-03).
 *
 * <p>Non-final so same-package tests can override {@link #tryAcquireSlot()} without starting a browser.
 */
class HeadlessSessionPool {

    private final RuntimeConfig config;
    private final Clock clock;
    private final Semaphore permits;
    private final AtomicInteger consecutiveAcquireFailures = new AtomicInteger();
    private volatile Instant circuitOpenUntil = Instant.EPOCH;
    private final CrawlerMetrics metrics;

    HeadlessSessionPool(RuntimeConfig config, Clock clock) {
        this(config, clock, null);
    }

    HeadlessSessionPool(RuntimeConfig config, Clock clock, CrawlerMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = metrics;
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
        if (metrics != null) {
            metrics.recordHeadlessAcquireTimeout();
        }
        int n = consecutiveAcquireFailures.incrementAndGet();
        if (n >= config.fetchHeadlessCircuitOpenThreshold()) {
            Instant now = clock.instant();
            boolean wasOpen = now.isBefore(circuitOpenUntil);
            circuitOpenUntil = now.plus(Duration.ofMinutes(1));
            consecutiveAcquireFailures.set(0);
            if (metrics != null && !wasOpen) {
                metrics.recordHeadlessCircuitOpened();
            }
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

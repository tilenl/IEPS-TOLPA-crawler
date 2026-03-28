/*
 * Per-domain robots cache, Bucket4j politeness, and HTTP overload backoff (TS-08, TS-03).
 *
 * Callers: HttpFetcher (robots + rate limit + overload signals), future TS-02 worker (outer tryAcquire).
 *
 * Invariants: {@link #ensureLoaded(String)} uses single-flight per domain; {@link #tryAcquire(String)} and
 * robots fetches share the same domain bucket (TS-08). Bucket spacing uses max(floor, Crawl-delay) after rules
 * load; entries are evicted from the bucket cache when crawl-delay changes.
 *
 * Created: 2026-03. Major revision: TS-08 Bucket4j + Caffeine + robots HTTP (replacing allow-all stub).
 */

package si.uni_lj.fri.wier.downloader.politeness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.downloader.fetch.CrawlerUserAgents;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;
import si.uni_lj.fri.wier.downloader.fetch.ManualHttpRedirects;

/**
 * Robots + per-domain rate limiting gate (TS-08, TS-03).
 *
 * <p>Thread-safety: safe for concurrent {@link #ensureLoaded(String)} / {@link #tryAcquire(String)} /
 * {@link #evaluate(String)} from virtual threads.
 */
public final class PolitenessGate implements RobotsTxtCache, RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(PolitenessGate.class);

    private static final BaseRobotRules ALLOW_ALL_RULES =
            new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

    private final RuntimeConfig config;
    private final HttpClient httpClient;
    private final SimpleRobotRulesParser robotsParser;
    /**
     * When non-null, first robots URL is {@code robotsBaseUrlOverride + "/robots.txt"} (loopback tests). Otherwise
     * {@code https://{domain}/robots.txt}.
     */
    private final String robotsBaseUrlOverride;

    private final Map<String, BaseRobotRules> rulesByDomain = new ConcurrentHashMap<>();
    /** Domains whose robots.txt load completed (success or fallback rules installed). */
    private final Map<String, Boolean> robotsReady = new ConcurrentHashMap<>();

    private final Cache<String, Bucket> bucketCache;

    /** Single-flight lock per domain for robots load. */
    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

    /** HTTP 429/503 exponential backoff: next eligible wall time (ms since epoch). */
    private final ConcurrentHashMap<String, Long> overloadUntilEpochMs = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> httpOverloadFailures = new ConcurrentHashMap<>();

    public PolitenessGate(RuntimeConfig config) {
        this(config, buildHttpClient(config), null);
    }

    /** Visible for tests: custom {@link HttpClient} (redirect policy, version). */
    public PolitenessGate(RuntimeConfig config, HttpClient httpClient) {
        this(config, httpClient, null);
    }

    /**
     * Loopback / test wiring: {@code robotsBaseUrlOverride} is origin without trailing slash (e.g. {@code
     * http://127.0.0.1:8080}); first fetch is {@code origin + "/robots.txt"}.
     */
    PolitenessGate(RuntimeConfig config, HttpClient httpClient, String robotsBaseUrlOverride) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.robotsBaseUrlOverride = robotsBaseUrlOverride;
        this.robotsParser = new SimpleRobotRulesParser();
        this.bucketCache =
                Caffeine.newBuilder()
                        .maximumSize(config.bucketsCacheMaxEntries())
                        .expireAfterAccess(Duration.ofHours(config.bucketsCacheTtlHours()))
                        .build();
    }

    private static HttpClient buildHttpClient(RuntimeConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.fetchConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Records HTTP status for overload backoff (TS-08). Safe to call after each content GET response.
     *
     * @param domain lower-case host key
     */
    @Override
    public void recordHttpResponse(String domain, int statusCode) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        if (statusCode == 429 || statusCode == 503) {
            int n = httpOverloadFailures.computeIfAbsent(domain, d -> new AtomicInteger()).incrementAndGet();
            long delayMs =
                    Math.min(
                            5000L * (1L << Math.min(n, 20)),
                            (long) config.rateLimitMaxBackoffMs());
            long until = System.currentTimeMillis() + delayMs;
            overloadUntilEpochMs.merge(domain, until, Math::max);
        } else if ((statusCode >= 200 && statusCode < 300) || statusCode == 404 || statusCode == 410) {
            httpOverloadFailures.remove(domain);
            overloadUntilEpochMs.remove(domain);
        }
    }

    @Override
    public void ensureLoaded(String domain) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        Object lock = loadLocks.computeIfAbsent(domain, d -> new Object());
        synchronized (lock) {
            if (Boolean.TRUE.equals(robotsReady.get(domain))) {
                return;
            }
            try {
                loadRobotsForDomain(domain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                installFallbackRules(domain, new SimpleRobotRulesParser().failedFetch(503));
            } catch (Exception e) {
                log.warn("robots fetch failed domain={} msg={}", domain, e.toString());
                installFallbackRules(domain, robotsParser.failedFetch(503));
            }
            robotsReady.put(domain, Boolean.TRUE);
        }
    }

    private void loadRobotsForDomain(String crawlDomain) throws IOException, InterruptedException {
        String currentUrl = initialRobotsUrl(crawlDomain);
        int redirectCount = 0;
        int maxRedirects = config.fetchMaxRedirects();
        Set<String> seen = new HashSet<>();

        while (true) {
            if (!seen.add(currentUrl)) {
                installFallbackRules(crawlDomain, robotsParser.failedFetch(503));
                return;
            }

            String hopHostKey = HostKeys.domainKey(currentUrl);
            blockUntilRateToken(hopHostKey);

            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(currentUrl))
                            .timeout(Duration.ofMillis(config.fetchReadTimeoutMs()))
                            .header("User-Agent", CrawlerUserAgents.FETCHER)
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            recordHttpResponse(hopHostKey, response.statusCode());
            int status = response.statusCode();

            if (ManualHttpRedirects.isRedirect(status)) {
                redirectCount++;
                if (redirectCount > maxRedirects) {
                    installFallbackRules(crawlDomain, robotsParser.failedFetch(503));
                    return;
                }
                String location =
                        response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    installFallbackRules(crawlDomain, robotsParser.failedFetch(status));
                    return;
                }
                try {
                    currentUrl = ManualHttpRedirects.resolveLocation(currentUrl, location);
                } catch (IllegalArgumentException e) {
                    installFallbackRules(crawlDomain, robotsParser.failedFetch(503));
                    return;
                }
                continue;
            }

            BaseRobotRules rules;
            if (status >= 200 && status < 300) {
                rules =
                        robotsParser.parseContent(
                                currentUrl,
                                response.body(),
                                StandardCharsets.UTF_8.name(),
                                CrawlerUserAgents.FETCHER);
            } else {
                rules = robotsParser.failedFetch(status);
            }
            rulesByDomain.put(crawlDomain, rules);
            bucketCache.invalidate(crawlDomain);
            return;
        }
    }

    private String initialRobotsUrl(String crawlDomain) {
        if (robotsBaseUrlOverride != null) {
            return robotsBaseUrlOverride + "/robots.txt";
        }
        return "https://" + crawlDomain + "/robots.txt";
    }

    private void installFallbackRules(String domain, BaseRobotRules rules) {
        rulesByDomain.put(domain, rules);
        bucketCache.invalidate(domain);
    }

    /**
     * Blocks the current thread until a politeness token is acquired for the robots GET (same bucket as content).
     */
    private void blockUntilRateToken(String domain) throws InterruptedException {
        for (;;) {
            RateLimitDecision decision = tryAcquireDecision(domain);
            if (!decision.isDelayed()) {
                return;
            }
            long waitMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(decision.waitNs()));
            Thread.sleep(Math.min(waitMs, 60_000L));
        }
    }

    @Override
    public RateLimitDecision tryAcquire(String domain) {
        if (domain == null || domain.isBlank()) {
            return RateLimitDecision.delayed(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
        }
        return tryAcquireDecision(domain);
    }

    private RateLimitDecision tryAcquireDecision(String domain) {
        Long until = overloadUntilEpochMs.get(domain);
        long now = System.currentTimeMillis();
        if (until != null && now < until) {
            return RateLimitDecision.delayed(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(until - now));
        }
        Bucket bucket = bucketCache.get(domain, this::createBucketForDomain);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return RateLimitDecision.allowed();
        }
        return RateLimitDecision.delayed(probe.getNanosToWaitForRefill());
    }

    private Bucket createBucketForDomain(String domain) {
        long spacingMs = spacingMillisForDomain(domain);
        long ms = Math.max(1L, spacingMs);
        Bandwidth bandwidth = Bandwidth.classic(1, Refill.intervally(1, Duration.ofMillis(ms)));
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /** TS-08: max(floor, robots Crawl-delay). */
    long spacingMillisForDomain(String domain) {
        long floor = config.rateLimitMinDelayMs();
        BaseRobotRules rules = rulesByDomain.get(domain);
        if (rules == null) {
            return floor;
        }
        long crawl = rules.getCrawlDelay();
        if (crawl == BaseRobotRules.UNSET_CRAWL_DELAY || crawl <= 0) {
            return floor;
        }
        return Math.max(floor, crawl);
    }

    @Override
    public RobotDecision evaluate(String canonicalUrl) {
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            return RobotDecision.temporaryDeny(Instant.now(), "INVALID_URL");
        }
        String domain;
        try {
            domain = URI.create(canonicalUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return RobotDecision.temporaryDeny(Instant.now(), "INVALID_URL");
        }
        if (domain == null || domain.isBlank()) {
            return RobotDecision.temporaryDeny(Instant.now(), "INVALID_URL");
        }
        domain = domain.toLowerCase(Locale.ROOT);
        BaseRobotRules rules = rulesByDomain.getOrDefault(domain, ALLOW_ALL_RULES);
        URI u = URI.create(canonicalUrl);
        String path = u.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (rules.isAllowNone()) {
            return RobotDecision.disallowed("ROBOTS_DISALLOW_ALL");
        }
        if (!rules.isAllowed(path)) {
            return RobotDecision.disallowed("ROBOTS_DISALLOWED");
        }
        return RobotDecision.allowed();
    }

    @Override
    public BaseRobotRules getRulesForDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return ALLOW_ALL_RULES;
        }
        return rulesByDomain.getOrDefault(domain, ALLOW_ALL_RULES);
    }
}

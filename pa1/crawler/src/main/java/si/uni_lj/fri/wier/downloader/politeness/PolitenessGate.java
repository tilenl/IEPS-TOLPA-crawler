/*
 * TS-06 robots.txt policy (parse, cache, temporary deny, site metadata) plus TS-08 per-domain rate limits.
 *
 * Callers: HttpFetcher (ensureLoaded + evaluate per hop), EnqueueCoordinator (Stage A, TS-06), Main (composition).
 *
 * Invariants: {@link #ensureLoaded(String)} is single-flight per domain; robots GETs consume the same Bucket4j
 * token as content fetches for that hop host. Parsed rules live in a Caffeine cache with TTL/max entries from
 * TS-13; eviction drops cached policy so the next load starts fresh (deny counters do not survive eviction).
 * HTTP 4xx on robots.txt is allow-all by design (TS-06); 3xx/5xx and hop failures use bounded TEMPORARY_DENY.
 * {@link #evaluate(String)} without a cached policy for the URL host returns {@code TEMPORARY_DENY} (TS-06 contract).
 *
 * Created: 2026-03. Major revisions: TS-06 Caffeine rules cache, temporary deny, SiteMetadataSink; TS-08 buckets.
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsSiteMetadataSink;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.downloader.fetch.CrawlerUserAgents;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;
import si.uni_lj.fri.wier.downloader.fetch.ManualHttpRedirects;

/**
 * Robots policy gate with per-domain politeness (TS-06, TS-08, TS-03).
 *
 * <p>Thread-safety: safe for concurrent {@link #ensureLoaded(String)}, {@link #tryAcquire(String)}, and
 * {@link #evaluate(String)} from virtual threads.
 */
public final class PolitenessGate implements RobotsTxtCache, RateLimiterRegistry {

    private static final Logger log = LoggerFactory.getLogger(PolitenessGate.class);

    private static final BaseRobotRules ALLOW_ALL_RULES =
            new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

    private final RuntimeConfig config;
    private final HttpClient httpClient;
    private final SimpleRobotRulesParser robotsParser;
    private final Clock clock;
    /**
     * When non-null, first robots URL is {@code robotsBaseUrlOverride + "/robots.txt"} (loopback tests). Otherwise
     * {@code https://{domain}/robots.txt}.
     */
    private final String robotsBaseUrlOverride;
    /** Optional DB persistence for {@code crawldb.site} robots/sitemap columns (TS-06). */
    private final RobotsSiteMetadataSink siteMetadataSink;

    /** Domain-keyed robots policy; eviction clears all state for that domain (stakeholder decision). */
    private final Cache<String, RobotsDomainPolicy> robotsPolicyCache;

    private final Cache<String, Bucket> bucketCache;

    /** Single-flight lock per domain for robots load. */
    private final ConcurrentHashMap<String, Object> loadLocks = new ConcurrentHashMap<>();

    /** HTTP 429/503 exponential backoff: next eligible wall time (ms since epoch). */
    private final ConcurrentHashMap<String, Long> overloadUntilEpochMs = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> httpOverloadFailures = new ConcurrentHashMap<>();

    public PolitenessGate(RuntimeConfig config) {
        this(config, buildHttpClient(config), null, null, Clock.systemUTC());
    }

    /** Visible for tests: custom {@link HttpClient} (redirect policy, version). */
    public PolitenessGate(RuntimeConfig config, HttpClient httpClient) {
        this(config, httpClient, null, null, Clock.systemUTC());
    }

    /**
     * Loopback / test wiring: {@code robotsBaseUrlOverride} is origin without trailing slash (e.g. {@code
     * http://127.0.0.1:8080}); first fetch is {@code origin + "/robots.txt"}.
     */
    public PolitenessGate(RuntimeConfig config, HttpClient httpClient, String robotsBaseUrlOverride) {
        this(config, httpClient, robotsBaseUrlOverride, null, Clock.systemUTC());
    }

    /**
     * Full wiring for production and tests.
     *
     * @param siteMetadataSink may be {@code null} to skip DB writes (unit tests)
     * @param clock wall clock for deny windows (inject fixed clock in tests)
     */
    public PolitenessGate(
            RuntimeConfig config,
            HttpClient httpClient,
            String robotsBaseUrlOverride,
            RobotsSiteMetadataSink siteMetadataSink,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.robotsBaseUrlOverride = robotsBaseUrlOverride;
        this.siteMetadataSink = siteMetadataSink;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.robotsParser = new SimpleRobotRulesParser();
        this.robotsPolicyCache =
                Caffeine.newBuilder()
                        .maximumSize(config.robotsCacheMaxEntries())
                        .expireAfterWrite(Duration.ofHours(config.robotsCacheTtlHours()))
                        .build();
        this.bucketCache =
                Caffeine.newBuilder()
                        .maximumSize(config.bucketsCacheMaxEntries())
                        .expireAfterAccess(Duration.ofHours(config.bucketsCacheTtlHours()))
                        .build();
    }

    /** Shared HttpClient factory for CLI composition (TS-13). */
    public static HttpClient buildHttpClient(RuntimeConfig config) {
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
        String d = normalizeDomain(domain);
        if (d.isEmpty()) {
            return;
        }
        Object lock = loadLocks.computeIfAbsent(d, x -> new Object());
        synchronized (lock) {
            Instant now = clock.instant();
            RobotsDomainPolicy existing = robotsPolicyCache.getIfPresent(d);
            if (existing != null && existing.blockingDenyActive(now)) {
                // Still inside TS-06 temporary-deny window; do not hit the network again yet.
                return;
            }
            if (existing != null
                    && existing.kind() == RobotsDomainPolicy.Kind.TRANSIENT_FAILURE
                    && !existing.blockingDenyActive(now)) {
                // Deny window elapsed — retry fetch while keeping consecutive failure count for backoff.
                try {
                    loadAndCacheRobots(d, existing.consecutiveFailures());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    putTransientFailure(d, existing.consecutiveFailures(), "interrupted");
                } catch (Exception e) {
                    log.warn("robots refresh failed domain={} msg={}", d, e.toString());
                    putTransientFailure(d, existing.consecutiveFailures(), "exception");
                }
                return;
            }
            if (existing != null) {
                // Successful cache entry still valid (Caffeine TTL not expired).
                return;
            }
            try {
                loadAndCacheRobots(d, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                putTransientFailure(d, 0, "interrupted");
            } catch (Exception e) {
                log.warn("robots fetch failed domain={} msg={}", d, e.toString());
                putTransientFailure(d, 0, "exception");
            }
        }
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) {
            return "";
        }
        return domain.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Fetches robots.txt (hop-by-hop redirects), parses, updates cache, and optionally persists site metadata.
     *
     * @param priorFailures consecutive failures already recorded before this attempt (zero on first load)
     */
    private void loadAndCacheRobots(String crawlDomain, int priorFailures)
            throws IOException, InterruptedException {
        String currentUrl = initialRobotsUrl(crawlDomain);
        int redirectCount = 0;
        int maxRedirects = config.fetchMaxRedirects();
        Set<String> seen = new HashSet<>();

        while (true) {
            if (!seen.add(currentUrl)) {
                // Redirect loop — same normative bucket as other transient robots failures (TS-06).
                recordTransientHttpFailure(crawlDomain, priorFailures, "redirect_loop");
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
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            recordHttpResponse(hopHostKey, response.statusCode());
            int status = response.statusCode();

            if (ManualHttpRedirects.isRedirect(status)) {
                redirectCount++;
                if (redirectCount > maxRedirects) {
                    recordTransientHttpFailure(crawlDomain, priorFailures, "max_redirects");
                    return;
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    recordTransientHttpFailure(crawlDomain, priorFailures, "redirect_no_location");
                    return;
                }
                try {
                    currentUrl = ManualHttpRedirects.resolveLocation(currentUrl, location);
                } catch (IllegalArgumentException e) {
                    recordTransientHttpFailure(crawlDomain, priorFailures, "redirect_bad_location");
                    return;
                }
                continue;
            }

            byte[] body = response.body() != null ? response.body() : new byte[0];
            if (status >= 200 && status < 300) {
                BaseRobotRules rules;
                try {
                    rules =
                            robotsParser.parseContent(
                                    currentUrl,
                                    body,
                                    StandardCharsets.UTF_8.name(),
                                    CrawlerUserAgents.FETCHER);
                } catch (RuntimeException e) {
                    log.warn("robots parse failed domain={} msg={}", crawlDomain, e.toString());
                    recordTransientHttpFailure(crawlDomain, priorFailures, "parse_error");
                    return;
                }
                robotsPolicyCache.put(crawlDomain, RobotsDomainPolicy.parsed(rules));
                bucketCache.invalidate(crawlDomain);
                persistSiteMetadata(
                        crawlDomain, new String(body, StandardCharsets.UTF_8), formatSitemapContent(rules));
                return;
            }
            if (status >= 400 && status < 500) {
                // TS-06: 4xx means no robots file — allow-all; still persist raw body for diagnostics.
                robotsPolicyCache.put(crawlDomain, RobotsDomainPolicy.allowAll4xx(ALLOW_ALL_RULES));
                bucketCache.invalidate(crawlDomain);
                persistSiteMetadata(crawlDomain, new String(body, StandardCharsets.UTF_8), "");
                return;
            }
            // Final 3xx without following (should not happen) or 5xx — temporary deny-all window.
            recordTransientHttpFailure(crawlDomain, priorFailures, "http_" + status);
            return;
        }
    }

    private void recordTransientHttpFailure(String crawlDomain, int priorFailures, String reason) {
        int next = priorFailures + 1;
        Instant denyUntil = computeDenyUntil(next);
        robotsPolicyCache.put(
                crawlDomain,
                RobotsDomainPolicy.transientFailure(ALLOW_ALL_RULES, denyUntil, next));
        bucketCache.invalidate(crawlDomain);
        log.debug("robots transient domain={} reason={} failures={} denyUntil={}", crawlDomain, reason, next, denyUntil);
    }

    private void putTransientFailure(String crawlDomain, int priorFailures, String reason) {
        recordTransientHttpFailure(crawlDomain, priorFailures, reason);
    }

    /**
     * TS-06: initial failure uses retry minutes; each further failure multiplies delay by powers of two, adds
     * jitter, and clamps so {@code deny_until <= now + temporaryDenyMaxMinutes}.
     */
    private Instant computeDenyUntil(int consecutiveFailuresAfterThisFailure) {
        Instant now = clock.instant();
        long retryMs = Duration.ofMinutes(config.robotsTemporaryDenyRetryMinutes()).toMillis();
        long maxMs = Duration.ofMinutes(config.robotsTemporaryDenyMaxMinutes()).toMillis();
        int exp = Math.max(0, consecutiveFailuresAfterThisFailure - 1);
        long mult = 1L << Math.min(exp, 20);
        long delayMs = retryMs * mult;
        delayMs = Math.min(delayMs, maxMs);
        long jitter =
                config.retryJitterMs() > 0
                        ? ThreadLocalRandom.current().nextLong(0, (long) config.retryJitterMs() + 1L)
                        : 0L;
        delayMs = Math.min(delayMs + jitter, maxMs);
        delayMs = Math.max(1L, delayMs);
        Instant until = now.plusMillis(delayMs);
        Instant cap = now.plusMillis(maxMs);
        return until.isAfter(cap) ? cap : until;
    }

    private void persistSiteMetadata(String domain, String robotsUtf8, String sitemapText) {
        if (siteMetadataSink == null) {
            return;
        }
        try {
            siteMetadataSink.save(domain, robotsUtf8 != null ? robotsUtf8 : "", sitemapText != null ? sitemapText : "");
        } catch (RuntimeException e) {
            // Site metadata must not break crawling; surface for operators via logs (TS-15 later).
            log.warn("robots site metadata save failed domain={} msg={}", domain, e.toString());
        }
    }

    private static String formatSitemapContent(BaseRobotRules rules) {
        List<String> urls = rules.getSitemaps();
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        return urls.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("\n"));
    }

    private String initialRobotsUrl(String crawlDomain) {
        if (robotsBaseUrlOverride != null) {
            return robotsBaseUrlOverride + "/robots.txt";
        }
        return "https://" + crawlDomain + "/robots.txt";
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
        return tryAcquireDecision(normalizeDomain(domain));
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

    /** TS-08: max(floor, robots Crawl-delay) using cached rules for the domain. */
    long spacingMillisForDomain(String domain) {
        String d = normalizeDomain(domain);
        long floor = config.rateLimitMinDelayMs();
        RobotsDomainPolicy policy = robotsPolicyCache.getIfPresent(d);
        BaseRobotRules rules = policy != null ? policy.rules() : null;
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
            return RobotDecision.temporaryDeny(clock.instant(), "INVALID_URL");
        }
        final String domainKey;
        try {
            domainKey = HostKeys.domainKey(canonicalUrl.trim());
        } catch (IllegalArgumentException e) {
            return RobotDecision.temporaryDeny(clock.instant(), "INVALID_URL");
        }
        Instant now = clock.instant();
        RobotsDomainPolicy policy = robotsPolicyCache.getIfPresent(domainKey);
        if (policy == null) {
            // TS-06: evaluate without prior ensureLoaded(domain) is a contract violation — surface explicitly.
            return RobotDecision.temporaryDeny(now, "ROBOTS_NOT_LOADED");
        }
        if (policy.kind() == RobotsDomainPolicy.Kind.TRANSIENT_FAILURE && policy.blockingDenyActive(now)) {
            return RobotDecision.temporaryDeny(policy.denyUntil(), "ROBOTS_TRANSIENT_HTTP");
        }
        BaseRobotRules rules = policy.rules();
        return evaluatePathAllowance(rules, canonicalUrl);
    }

    private static RobotDecision evaluatePathAllowance(BaseRobotRules rules, String canonicalUrl) {
        URI u = URI.create(canonicalUrl.trim());
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
        String d = normalizeDomain(domain);
        if (d.isEmpty()) {
            return ALLOW_ALL_RULES;
        }
        RobotsDomainPolicy policy = robotsPolicyCache.getIfPresent(d);
        if (policy == null) {
            return ALLOW_ALL_RULES;
        }
        return policy.rules();
    }

    /**
     * Test and diagnostic hook: evicts cached robots policy for a domain as if Caffeine removed the entry (TS-06:
     * deny/failure counters do not survive eviction).
     */
    public void invalidateRobotsPolicyCacheEntry(String domain) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        robotsPolicyCache.invalidate(normalizeDomain(domain));
    }
}

/*
 * TS-03 fetch: manual redirect chain, per-hop robots + politeness, plain HTTP vs headless, lease-aware waits.
 *
 * Callers: TS-02 worker (future). Composes {@link PolitenessGate}, {@link HeadlessSessionPool}, {@link java.net.http.HttpClient}.
 *
 * Invariants: {@link si.uni_lj.fri.wier.contracts.FetchRequest#canonicalUrl()} remains the storage key; final URL may differ
 * after redirects. {@link si.uni_lj.fri.wier.error.CrawlerErrorCategory#FETCH_TIMEOUT} is used both for true timeouts and
 * redirect-chain exhaustion (project convention).
 *
 * Created: 2026-03. Major revision: TS-03 implementation (replaces NOT_IMPLEMENTED stub).
 */

package si.uni_lj.fri.wier.downloader.fetch;

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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchMode;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.Fetcher;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;

/**
 * Hop-by-hop HTTP(S) fetch with politeness and optional headless escalation (TS-03).
 */
public final class HttpFetcher implements Fetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final RuntimeConfig config;
    private final RobotsTxtCache robotsTxtCache;
    private final RateLimiterRegistry rateLimiter;
    private final HttpClient httpClient;
    private final HeadlessSessionPool headlessPool;
    private final Clock clock;

    /**
     * Production wiring: typically pass the same {@link PolitenessGate} for both robots and rate limits.
     */
    public HttpFetcher(RuntimeConfig config, RobotsTxtCache robotsTxtCache, RateLimiterRegistry rateLimiter) {
        this(
                config,
                robotsTxtCache,
                rateLimiter,
                Clock.systemUTC(),
                buildHttpClient(config),
                new HeadlessSessionPool(config, Clock.systemUTC()));
    }

    /** Convenience: one {@link PolitenessGate} implements both contracts. */
    public HttpFetcher(RuntimeConfig config, PolitenessGate politeness) {
        this(config, politeness, politeness);
    }

    /** Test hook: fixed clock and injected HTTP client / pool. */
    HttpFetcher(
            RuntimeConfig config,
            RobotsTxtCache robotsTxtCache,
            RateLimiterRegistry rateLimiter,
            Clock clock,
            HttpClient httpClient,
            HeadlessSessionPool headlessPool) {
        this.config = Objects.requireNonNull(config, "config");
        this.robotsTxtCache = Objects.requireNonNull(robotsTxtCache, "robotsTxtCache");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.headlessPool = Objects.requireNonNull(headlessPool, "headlessPool");
    }

    private static HttpClient buildHttpClient(RuntimeConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.fetchConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Redirect hop limit from TS-13 (exposed for tests). */
    public int maxRedirects() {
        return config.fetchMaxRedirects();
    }

    public static HttpFetcher from(RuntimeConfig config, PolitenessGate politeness) {
        return new HttpFetcher(config, politeness, politeness);
    }

    @Override
    public FetchResult fetch(FetchRequest request) throws FetchException {
        Objects.requireNonNull(request, "request");
        String canonicalUrl = request.canonicalUrl();
        String workerId = request.workerId() == null ? "" : request.workerId();
        Instant claimExpiresAt = request.claimExpiresAt();
        boolean outerRateOk = request.firstHopRateLimitSatisfied();

        String currentUrl = canonicalUrl;
        int hopIndex = 0;
        int redirectCount = 0;
        Set<String> seen = new HashSet<>();

        while (true) {
            if (!seen.add(currentUrl)) {
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_TIMEOUT.name(),
                        "redirect loop detected url=" + currentUrl);
            }

            String domain = HostKeys.domainKey(currentUrl);
            robotsTxtCache.ensureLoaded(domain);

            RobotDecision robot = robotsTxtCache.evaluate(currentUrl);
            if (robot.type() == RobotDecisionType.DISALLOWED) {
                throw new FetchException(
                        CrawlerErrorCategory.ROBOTS_DISALLOWED.name(),
                        robot.reason() != null ? robot.reason() : "robots disallowed");
            }
            if (robot.type() == RobotDecisionType.TEMPORARY_DENY) {
                throw new FetchException(
                        CrawlerErrorCategory.ROBOTS_TRANSIENT.name(),
                        robot.reason() != null ? robot.reason() : "robots transient");
            }

            boolean skipRateThisHop = hopIndex == 0 && outerRateOk;
            if (!skipRateThisHop) {
                applyPolitenessOrOverload(domain, claimExpiresAt, workerId, currentUrl, hopIndex);
            }

            log.debug(
                    "fetchHop url={} domain={} workerId={} hop={} canonical={}",
                    currentUrl,
                    domain,
                    workerId,
                    hopIndex,
                    canonicalUrl);

            final HttpResponse<byte[]> resp;
            try {
                resp = sendPlainGet(currentUrl);
            } catch (IOException e) {
                throw new FetchException(CrawlerErrorCategory.FETCH_TIMEOUT.name(), "io: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(CrawlerErrorCategory.FETCH_TIMEOUT.name(), "interrupted", e);
            }

            int status = resp.statusCode();
            rateLimiter.recordHttpResponse(domain, status);

            if (isRedirect(status)) {
                redirectCount++;
                if (redirectCount > config.fetchMaxRedirects()) {
                    throw new FetchException(
                            CrawlerErrorCategory.FETCH_TIMEOUT.name(),
                            "max redirect hops exceeded limit=" + config.fetchMaxRedirects());
                }
                String location = firstHeader(resp, "Location");
                if (location == null || location.isBlank()) {
                    throw new FetchException(
                            CrawlerErrorCategory.FETCH_HTTP_CLIENT.name(),
                            "redirect without Location url=" + currentUrl);
                }
                currentUrl = resolveLocation(currentUrl, location);
                hopIndex++;
                continue;
            }

            String contentType = firstHeader(resp, "Content-Type");
            String bodyText = decodeBody(resp, contentType);

            if (status == 429 || status == 503) {
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                        "http overload status=" + status + " url=" + currentUrl);
            }
            if (status >= 500) {
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_TIMEOUT.name(),
                        "server error status=" + status + " url=" + currentUrl);
            }
            if (status >= 400) {
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_HTTP_CLIENT.name(),
                        "client error status=" + status + " url=" + currentUrl);
            }

            FetchResult plain =
                    new FetchResult(
                            status,
                            contentType == null ? "" : contentType,
                            bodyText,
                            clock.instant(),
                            FetchMode.PLAIN_HTTP,
                            currentUrl.equals(canonicalUrl) ? null : currentUrl);

            if (shouldEscalateHeadless(domain, plain) && !headlessPool.isCircuitOpen()) {
                log.info(
                        "FETCH_INCOMPLETE_SHELL url={} domain={} workerId={} hop={}",
                        canonicalUrl,
                        domain,
                        workerId,
                        hopIndex);
                try {
                    return headlessFetch(canonicalUrl, currentUrl, domain, workerId, hopIndex);
                } catch (FetchException e) {
                    if (CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED.name().equals(e.category())) {
                        throw e;
                    }
                    log.debug("headless escalation failed: {}", e.toString());
                }
            }

            return plain;
        }
    }

    private HttpResponse<byte[]> sendPlainGet(String url) throws IOException, InterruptedException {
        HttpRequest req =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(config.fetchReadTimeoutMs()))
                        .header("User-Agent", CrawlerUserAgents.FETCHER)
                        .GET()
                        .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean isRedirect(int code) {
        return code == 301
                || code == 302
                || code == 303
                || code == 307
                || code == 308;
    }

    private static String firstHeader(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private String decodeBody(HttpResponse<byte[]> resp, String contentType) {
        byte[] body = resp.body();
        if (body == null) {
            return "";
        }
        return new String(body, charsetFromContentType(contentType));
    }

    private static java.nio.charset.Charset charsetFromContentType(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        int idx = ct.indexOf("charset=");
        if (idx < 0) {
            return StandardCharsets.UTF_8;
        }
        String cs = ct.substring(idx + "charset=".length()).trim();
        int semi = cs.indexOf(';');
        if (semi > 0) {
            cs = cs.substring(0, semi).trim();
        }
        try {
            return java.nio.charset.Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private void applyPolitenessOrOverload(
            String domain,
            Instant claimExpiresAt,
            String workerId,
            String url,
            int hopIndex)
            throws FetchException {
        for (;;) {
            RateLimitDecision d = rateLimiter.tryAcquire(domain);
            if (!d.isDelayed()) {
                return;
            }
            long waitMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(d.waitNs()));
            long persistReserveMs = config.frontierLeaseSeconds() * 1000L / 4;
            long remainingMs = Duration.between(clock.instant(), claimExpiresAt).toMillis();
            if (waitMs + persistReserveMs > remainingMs) {
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                        "politeness wait exceeds lease margin waitMs="
                                + waitMs
                                + " remainingMs="
                                + remainingMs
                                + " url="
                                + url
                                + " hop="
                                + hopIndex
                                + " workerId="
                                + workerId);
            }
            try {
                Thread.sleep(Math.min(waitMs, 60_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(CrawlerErrorCategory.FETCH_TIMEOUT.name(), "interrupted during wait", e);
            }
        }
    }

    private static String resolveLocation(String currentAbsolute, String locationHeader) throws FetchException {
        try {
            URI base = URI.create(currentAbsolute);
            URI resolved = base.resolve(locationHeader.trim());
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            throw new FetchException(
                    CrawlerErrorCategory.FETCH_TIMEOUT.name(), "bad Location header: " + locationHeader, e);
        }
    }

    private boolean shouldEscalateHeadless(String domain, FetchResult plain) {
        if (HostKeys.isGitHubHost(domain)) {
            return false;
        }
        String ct = plain.contentType() == null ? "" : plain.contentType().toLowerCase(Locale.ROOT);
        if (!ct.contains("text/html")) {
            return false;
        }
        String body = plain.body() == null ? "" : plain.body();
        String b = body.trim();
        return b.length() < 400 || !b.toLowerCase(Locale.ROOT).contains("<a ");
    }

    private FetchResult headlessFetch(
            String canonicalUrl, String currentUrl, String domain, String workerId, int hopIndex)
            throws FetchException {
        boolean acquired = false;
        WebDriver driver = null;
        try {
            try {
                if (!headlessPool.tryAcquireSlot()) {
                    throw new FetchException(
                            CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED.name(),
                            "headless slot not acquired domain="
                                    + domain
                                    + " workerId="
                                    + workerId
                                    + " hop="
                                    + hopIndex);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException(
                        CrawlerErrorCategory.FETCH_TIMEOUT.name(), "interrupted acquiring headless", e);
            }
            acquired = true;
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu");
            options.addArguments("--user-agent=" + CrawlerUserAgents.FETCHER);
            driver = new ChromeDriver(options);
            driver.get(currentUrl);
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            String html = driver.getPageSource();
            return new FetchResult(
                    200,
                    "text/html",
                    html,
                    clock.instant(),
                    FetchMode.HEADLESS,
                    currentUrl.equals(canonicalUrl) ? null : currentUrl);
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException(
                    CrawlerErrorCategory.FETCH_TIMEOUT.name(), "headless render failed: " + e.getMessage(), e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
            if (acquired) {
                headlessPool.releaseSlot();
            }
        }
    }
}

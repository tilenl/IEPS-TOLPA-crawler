package si.uni_lj.fri.wier.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Shared data contracts for crawler interfaces ({@code TS-01}).
 * <p>
 * Types are nested under {@code Contracts} to keep the contract surface in one place; see
 * {@code ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/technical-specifications/TS-01-interface-contracts.md}.
 * {@code FrontierRow.attemptCount} aligns with claim/retry fields in {@code TS-07}.
 */
public final class Contracts {
    private Contracts() {}

    public record FrontierRow(
            long pageId,
            String url,
            long siteId,
            double relevanceScore,
            /** Included for dequeue/claim SQL ({@code TS-07}); not listed in minimal TS-01 one-liner. */
            int attemptCount
    ) {}

    public enum FetchMode {
        PLAIN_HTTP,
        HEADLESS
    }

    public record FetchResult(
            String canonicalUrl,
            int statusCode,
            String contentType,
            Optional<String> body,
            Instant fetchedAt,
            FetchMode fetchMode
    ) {}

    public static class FetchException extends Exception {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record CanonicalizationResult(
            boolean ok,
            Optional<String> canonicalUrl,
            Optional<String> error
    ) {
        public static CanonicalizationResult success(String canonicalUrl) {
            return new CanonicalizationResult(true, Optional.of(canonicalUrl), Optional.empty());
        }

        public static CanonicalizationResult failure(String error) {
            return new CanonicalizationResult(false, Optional.empty(), Optional.ofNullable(error));
        }
    }

    public record DiscoveredUrl(
            String rawUrl,
            String baseUrl,
            long fromPageId,
            String anchorText,
            String contextText
    ) {}

    public record ImageRef(
            String url,
            String altText
    ) {}

    public record ParseResult(
            List<DiscoveredUrl> discoveredUrls,
            List<ImageRef> images
    ) {}

    public record FetchContext(
            long pageId,
            String canonicalUrl,
            long siteId,
            int attempt,
            Instant claimedAt
    ) {}

    public sealed interface RateLimitDecision permits RateLimitDecision.Allowed, RateLimitDecision.Delayed {
        record Allowed() implements RateLimitDecision {}

        record Delayed(long waitNs) implements RateLimitDecision {}
    }

    public enum RobotDecisionType {
        ALLOWED,
        DISALLOWED,
        TEMPORARY_DENY
    }

    public record RobotDecision(
            RobotDecisionType type,
            Optional<String> reason,
            Optional<Instant> denyUntil
    ) {
        public static RobotDecision allowed() {
            return new RobotDecision(RobotDecisionType.ALLOWED, Optional.empty(), Optional.empty());
        }

        public static RobotDecision disallowed(String reason) {
            return new RobotDecision(RobotDecisionType.DISALLOWED, Optional.ofNullable(reason), Optional.empty());
        }

        public static RobotDecision temporaryDeny(String reason, Instant denyUntil) {
            return new RobotDecision(RobotDecisionType.TEMPORARY_DENY, Optional.ofNullable(reason), Optional.ofNullable(denyUntil));
        }
    }

    public enum PageOutcomeType {
        HTML,
        BINARY,
        DUPLICATE,
        ERROR,
        RETRY
    }

    public record PersistOutcome(
            long pageId,
            PageOutcomeType outcomeType
    ) {}

    public record LinkInsertResult(
            boolean inserted
    ) {}

    public record InsertFrontierResult(
            boolean inserted,
            long pageId
    ) {}

    public record IngestResult(
            int accepted,
            int rejected
    ) {}
}


/*
 * TS-04 relevance scoring from {@code crawler.scoring.keywordConfig} JSON (same shape as
 * {@link si.uni_lj.fri.wier.config.ScoringKeywordConfigValidator}).
 *
 * Callers: {@link HtmlParser} (per-link scoring); {@link si.uni_lj.fri.wier.app.PreferentialCrawler} constructs the
 * scorer for the crawl pipeline.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import si.uni_lj.fri.wier.contracts.RelevanceScorer;

/**
 * Keyword overlap scorer: each primary or secondary keyword adds its tier weight per non-overlapping substring
 * occurrence in the normalized haystack (URL + anchor + context), capped per keyword by {@link
 * #maxOccurrencesPerKeyword}. Scores are non-negative; the sum is not globally capped.
 */
public final class KeywordRelevanceScorer implements RelevanceScorer {

    /** Default increment when a primary keyword substring matches (TS-13 default), per counted occurrence. */
    public static final double DEFAULT_PRIMARY_WEIGHT = 0.18;

    /** Default increment when a secondary keyword substring matches (TS-13 default), per counted occurrence. */
    public static final double DEFAULT_SECONDARY_WEIGHT = 0.09;

    /**
     * Default cap on counted occurrences per keyword per {@link #compute} call. Chosen so default {@code
     * crawler.scoring.seedRelevanceScore} stays above the worst-case keyword score for the repo keyword list at default
     * weights (see {@link si.uni_lj.fri.wier.config.RuntimeConfig#validate()}).
     */
    public static final int DEFAULT_MAX_OCCURRENCES_PER_KEYWORD = 128;

    private final List<String> primary;
    private final List<String> secondary;
    private final double primaryWeight;
    private final double secondaryWeight;
    private final int maxOccurrencesPerKeyword;

    /**
     * Loads keywords with {@link #DEFAULT_PRIMARY_WEIGHT}, {@link #DEFAULT_SECONDARY_WEIGHT}, and {@link
     * #DEFAULT_MAX_OCCURRENCES_PER_KEYWORD}.
     *
     * @param keywordConfigPath path validated at startup by {@link si.uni_lj.fri.wier.config.RuntimeConfig#validate()}
     */
    public KeywordRelevanceScorer(Path keywordConfigPath) throws IOException {
        this(keywordConfigPath, DEFAULT_PRIMARY_WEIGHT, DEFAULT_SECONDARY_WEIGHT, DEFAULT_MAX_OCCURRENCES_PER_KEYWORD);
    }

    /**
     * Loads keywords with the given weights and {@link #DEFAULT_MAX_OCCURRENCES_PER_KEYWORD}.
     *
     * @param keywordConfigPath path validated at startup
     * @param primaryWeight strictly positive per primary occurrence (before cap)
     * @param secondaryWeight strictly positive per secondary occurrence (before cap)
     */
    public KeywordRelevanceScorer(Path keywordConfigPath, double primaryWeight, double secondaryWeight)
            throws IOException {
        this(keywordConfigPath, primaryWeight, secondaryWeight, DEFAULT_MAX_OCCURRENCES_PER_KEYWORD);
    }

    /**
     * Loads and normalizes keywords (trim, lower case, dedupe preserving first-seen order) from a UTF-8 JSON file.
     *
     * @param keywordConfigPath path validated at startup
     * @param primaryWeight strictly positive per primary occurrence (before cap)
     * @param secondaryWeight strictly positive per secondary occurrence (before cap)
     * @param maxOccurrencesPerKeyword maximum non-overlapping occurrences counted per keyword (must be {@code >= 1})
     */
    public KeywordRelevanceScorer(
            Path keywordConfigPath, double primaryWeight, double secondaryWeight, int maxOccurrencesPerKeyword)
            throws IOException {
        Objects.requireNonNull(keywordConfigPath, "keywordConfigPath");
        if (!(primaryWeight > 0.0) || !(secondaryWeight > 0.0)) {
            throw new IllegalArgumentException("primaryWeight and secondaryWeight must be > 0");
        }
        if (maxOccurrencesPerKeyword < 1) {
            throw new IllegalArgumentException("maxOccurrencesPerKeyword must be >= 1");
        }
        this.primaryWeight = primaryWeight;
        this.secondaryWeight = secondaryWeight;
        this.maxOccurrencesPerKeyword = maxOccurrencesPerKeyword;
        String text = Files.readString(keywordConfigPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        this.primary = readDedupedKeywordList(keywordConfigPath, "primary", root);
        this.secondary = readDedupedKeywordList(keywordConfigPath, "secondary", root);
    }

    /**
     * Deduped tier sizes from the same file normalization as the scorer (for startup validation: seed score must
     * exceed worst-case capped occurrence sum).
     */
    public record KeywordTierCounts(int primaryCount, int secondaryCount) {
        /**
         * Upper bound on {@link KeywordRelevanceScorer#compute} for the given weights if every keyword appears {@code
         * maxOccurrencesPerKeyword} times.
         */
        public double maxKeywordScore(
                double primaryWeight, double secondaryWeight, int maxOccurrencesPerKeyword) {
            return primaryCount * primaryWeight * maxOccurrencesPerKeyword
                    + secondaryCount * secondaryWeight * maxOccurrencesPerKeyword;
        }
    }

    /**
     * Reads {@code primary} and {@code secondary} array lengths after trim/lowercase/dedupe, without constructing a
     * full scorer.
     */
    public static KeywordTierCounts readTierCounts(Path keywordConfigPath) throws IOException {
        Objects.requireNonNull(keywordConfigPath, "keywordConfigPath");
        String text = Files.readString(keywordConfigPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        int np = readDedupedKeywordList(keywordConfigPath, "primary", root).size();
        int ns = readDedupedKeywordList(keywordConfigPath, "secondary", root).size();
        return new KeywordTierCounts(np, ns);
    }

    private static List<String> readDedupedKeywordList(Path path, String name, JSONObject root) {
        if (!root.has(name)) {
            throw new IllegalArgumentException("crawler.scoring.keywordConfig missing " + name + " in " + path);
        }
        JSONArray arr = root.getJSONArray(name);
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.getString(i).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) {
                dedup.add(s);
            }
        }
        if (dedup.isEmpty()) {
            throw new IllegalArgumentException("crawler.scoring.keywordConfig " + name + " has no usable keywords");
        }
        return List.copyOf(dedup);
    }

    @Override
    public double compute(String canonicalUrl, String anchorText, String contextText) {
        String url = canonicalUrl == null ? "" : canonicalUrl.toLowerCase(Locale.ROOT);
        String anchor = anchorText == null ? "" : anchorText.toLowerCase(Locale.ROOT);
        String ctx = contextText == null ? "" : contextText.toLowerCase(Locale.ROOT);
        // Single haystack keeps scoring deterministic and cheap for assignment-scale pages.
        String haystack = url + " " + anchor + " " + ctx;

        double score = 0.0;
        for (String kw : primary) {
            int n = Math.min(countNonOverlapping(haystack, kw), maxOccurrencesPerKeyword);
            score += primaryWeight * n;
        }
        for (String kw : secondary) {
            int n = Math.min(countNonOverlapping(haystack, kw), maxOccurrencesPerKeyword);
            score += secondaryWeight * n;
        }
        return score;
    }

    /**
     * Non-overlapping substring occurrences: after each match, search resumes at {@code index + keyword.length()}.
     */
    public static int countNonOverlapping(String haystack, String keyword) {
        if (keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(keyword, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + keyword.length();
        }
        return count;
    }
}

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
 * Keyword overlap scorer: each matching primary or secondary keyword adds a configurable weight. Scores are
 * non-negative and not capped; sum all hits so highly relevant text ranks above sparse matches.
 */
public final class KeywordRelevanceScorer implements RelevanceScorer {

    /** Default increment when a primary keyword substring is found (TS-13 default). */
    public static final double DEFAULT_PRIMARY_WEIGHT = 0.18;

    /** Default increment when a secondary keyword substring is found (TS-13 default). */
    public static final double DEFAULT_SECONDARY_WEIGHT = 0.09;

    private final List<String> primary;
    private final List<String> secondary;
    private final double primaryWeight;
    private final double secondaryWeight;

    /**
     * Loads keywords with {@link #DEFAULT_PRIMARY_WEIGHT} and {@link #DEFAULT_SECONDARY_WEIGHT}.
     *
     * @param keywordConfigPath path validated at startup by {@link si.uni_lj.fri.wier.config.RuntimeConfig#validate()}
     */
    public KeywordRelevanceScorer(Path keywordConfigPath) throws IOException {
        this(keywordConfigPath, DEFAULT_PRIMARY_WEIGHT, DEFAULT_SECONDARY_WEIGHT);
    }

    /**
     * Loads and normalizes keywords (trim, lower case, dedupe preserving first-seen order) from a UTF-8 JSON file.
     *
     * @param keywordConfigPath path validated at startup
     * @param primaryWeight strictly positive per primary hit
     * @param secondaryWeight strictly positive per secondary hit
     */
    public KeywordRelevanceScorer(Path keywordConfigPath, double primaryWeight, double secondaryWeight)
            throws IOException {
        Objects.requireNonNull(keywordConfigPath, "keywordConfigPath");
        if (!(primaryWeight > 0.0) || !(secondaryWeight > 0.0)) {
            throw new IllegalArgumentException("primaryWeight and secondaryWeight must be > 0");
        }
        this.primaryWeight = primaryWeight;
        this.secondaryWeight = secondaryWeight;
        String text = Files.readString(keywordConfigPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        this.primary = readDedupedKeywordList(keywordConfigPath, "primary", root);
        this.secondary = readDedupedKeywordList(keywordConfigPath, "secondary", root);
    }

    /**
     * Deduped tier sizes from the same file normalization as the scorer (for startup validation: seed score must
     * exceed {@code primaryCount * primaryWeight + secondaryCount * secondaryWeight}).
     */
    public record KeywordTierCounts(int primaryCount, int secondaryCount) {
        /** Maximum possible {@link #compute} result for the given per-hit weights (every keyword matches). */
        public double maxKeywordScore(double primaryWeight, double secondaryWeight) {
            return primaryCount * primaryWeight + secondaryCount * secondaryWeight;
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
            if (containsKeyword(haystack, kw)) {
                score += primaryWeight;
            }
        }
        for (String kw : secondary) {
            if (containsKeyword(haystack, kw)) {
                score += secondaryWeight;
            }
        }
        return score;
    }

    /**
     * Substring match: phrases from JSON are contiguous substrings in the normalized haystack (singletons match inside
     * hyphenated or compound tokens).
     */
    private static boolean containsKeyword(String haystack, String keyword) {
        return haystack.contains(keyword);
    }
}

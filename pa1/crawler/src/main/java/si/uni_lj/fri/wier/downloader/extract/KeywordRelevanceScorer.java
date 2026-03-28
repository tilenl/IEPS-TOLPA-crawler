/*
 * TS-04 relevance scoring from {@code crawler.scoring.keywordConfig} JSON (same shape as
 * {@link si.uni_lj.fri.wier.config.ScoringKeywordConfigValidator}).
 *
 * Callers: {@link HtmlParser}, seed bootstrap in {@link si.uni_lj.fri.wier.app.PreferentialCrawler}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import si.uni_lj.fri.wier.contracts.RelevanceScorer;

/**
 * Keyword overlap scorer: primary keywords weigh more than secondary; result clamped to {@code [0.0, 1.0]}.
 */
public final class KeywordRelevanceScorer implements RelevanceScorer {

    private static final double PRIMARY_WEIGHT = 0.18;
    private static final double SECONDARY_WEIGHT = 0.09;
    private static final double SCORE_CAP = 1.0;

    private final List<String> primary;
    private final List<String> secondary;

    /**
     * Loads and normalizes keywords (trim, lower case) from a UTF-8 JSON file.
     *
     * @param keywordConfigPath path validated at startup by {@link si.uni_lj.fri.wier.config.RuntimeConfig#validate()}
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if JSON structure is invalid
     */
    public KeywordRelevanceScorer(Path keywordConfigPath) throws IOException {
        Objects.requireNonNull(keywordConfigPath, "keywordConfigPath");
        String text = Files.readString(keywordConfigPath, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(text);
        this.primary = readKeywordArray(keywordConfigPath, "primary", root);
        this.secondary = readKeywordArray(keywordConfigPath, "secondary", root);
    }

    private static List<String> readKeywordArray(Path path, String name, JSONObject root) {
        if (!root.has(name)) {
            throw new IllegalArgumentException("crawler.scoring.keywordConfig missing " + name + " in " + path);
        }
        JSONArray arr = root.getJSONArray(name);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.getString(i).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("crawler.scoring.keywordConfig " + name + " has no usable keywords");
        }
        return List.copyOf(out);
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
                score += PRIMARY_WEIGHT;
            }
        }
        for (String kw : secondary) {
            if (containsKeyword(haystack, kw)) {
                score += SECONDARY_WEIGHT;
            }
        }
        return Math.min(SCORE_CAP, score);
    }

    /**
     * Substring match is sufficient for assignment keywords (multi-word phrases from JSON are matched as
     * contiguous substrings in the normalized haystack).
     */
    private static boolean containsKeyword(String haystack, String keyword) {
        return haystack.contains(keyword);
    }
}

/*
 * Startup validation for crawler.scoring.keywordConfig (TS-13 normative JSON shape).
 *
 * Uses org.json only for this fixed schema; RelevanceScorer (TS-04) may re-read the same file later.
 *
 * Callers: RuntimeConfig#validate. Owned by TS-13.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Validates UTF-8 JSON at {@code crawler.scoring.keywordConfig}: object root with non-empty {@code primary}
 * and {@code secondary} string arrays. Trims, lowercases, and deduplicates in memory to prove the file can be
 * normalized per TS-13; results are not stored on {@link RuntimeConfig}.
 */
public final class ScoringKeywordConfigValidator {

    private ScoringKeywordConfigValidator() {}

    /**
     * Reads and validates the keyword file from a filesystem path (relative to JVM working directory or
     * absolute).
     *
     * @param path non-null path to a UTF-8 JSON file
     * @throws IllegalArgumentException if the file is missing, invalid JSON, or violates TS-13 structure
     * @throws IOException if the file cannot be read
     */
    public static void validate(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig must refer to an existing file: " + path.toAbsolutePath());
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalArgumentException("crawler.scoring.keywordConfig: invalid JSON: " + e.getMessage(), e);
        }
        if (!root.has("primary") || !root.has("secondary")) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: JSON must contain primary and secondary arrays");
        }
        validateArray(path, "primary", root.get("primary"));
        validateArray(path, "secondary", root.get("secondary"));
    }

    private static void validateArray(Path path, String name, Object raw) {
        if (!(raw instanceof JSONArray arr)) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: " + name + " must be a JSON array in " + path.toAbsolutePath());
        }
        if (arr.length() == 0) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: " + name + " must be non-empty (TS-13) in " + path.toAbsolutePath());
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            Object el = arr.opt(i);
            if (!(el instanceof String s)) {
                throw new IllegalArgumentException(
                        "crawler.scoring.keywordConfig: " + name + "[" + i + "] must be a string in "
                                + path.toAbsolutePath());
            }
            String t = s.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException(
                        "crawler.scoring.keywordConfig: " + name + "[" + i + "] must not be blank after trim");
            }
            normalized.add(t.toLowerCase(Locale.ROOT));
        }
        // After trim/lowercase/dedupe, TS-13 still requires non-empty categories (all duplicates would empty a set).
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: " + name + " has no usable keywords after normalization");
        }
    }
}

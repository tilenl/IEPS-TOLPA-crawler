package si.uni_lj.fri.wier.unit.downloader.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import org.junit.jupiter.api.io.TempDir;

class KeywordRelevanceScorerTest {

    @Test
    void compute_singletonMatchesInsideHyphenatedCompound(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["semantic"],"secondary":["detection"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 0.2, 0.1);
        double score = s.compute("https://x/y", "semantic-segmentation paper", "");
        assertEquals(0.2, score, 1e-9);
    }

    @Test
    void compute_sumNotCapped_exceedsOne(@TempDir Path dir) throws Exception {
        // Multi-character tokens so singleton letters are not counted inside words like "and".
        Path kw = writeKeywords(dir, """
                {"primary":["alpha","beta","gamma","delta","phi"],"secondary":["chi"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 0.3, 0.2);
        String hay = "alpha beta gamma delta phi and chi";
        double score = s.compute(hay, hay, hay);
        assertEquals(5 * 3 * 0.3 + 3 * 0.2, score, 1e-9);
        assertTrue(score > 1.0);
    }

    @Test
    void compute_primaryRepeatedInUrl_multipliesWeight(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["segmentation"],"secondary":["detection"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 0.2, 0.1, 128);
        double score = s.compute("https://x/segmentation-segmentation-segmentation-segmentation-segmentation", "", "");
        assertEquals(5 * 0.2, score, 1e-9);
    }

    @Test
    void compute_nonOverlappingSubstringCountsOnce(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["aa"],"secondary":["z"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 1.0, 1.0, 128);
        assertEquals(1.0, s.compute("https://x/aaa", "", ""), 1e-9);
    }

    @Test
    void compute_occurrenceCap_appliesPerKeyword(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["xy"],"secondary":["z"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 1.0, 1.0, 3);
        double score = s.compute("xyxyxyxyxy", "", "");
        assertEquals(3.0, score, 1e-9);
    }

    @Test
    void countNonOverlapping_exposedForEdgeCases() {
        assertEquals(1, KeywordRelevanceScorer.countNonOverlapping("aaa", "aa"));
        assertEquals(3, KeywordRelevanceScorer.countNonOverlapping("ababab", "ab"));
    }

    @Test
    void load_deduplicatesRepeatedLines(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["foo","foo","bar"],"secondary":["baz"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 1.0, 1.0);
        assertEquals(2.0, s.compute("foo bar", "", ""), 1e-9);
    }

    @Test
    void readTierCounts_matchesDedupedListSize(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["a","a"],"secondary":["b","b","b"]}
                """);
        KeywordRelevanceScorer.KeywordTierCounts t = KeywordRelevanceScorer.readTierCounts(kw);
        assertEquals(1, t.primaryCount());
        assertEquals(1, t.secondaryCount());
        assertEquals(0.5, t.maxKeywordScore(0.3, 0.2, 1), 1e-9);
    }

    @Test
    void constructor_rejectsNonPositiveWeights(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["x"],"secondary":["y"]}
                """);
        assertThrows(IllegalArgumentException.class, () -> new KeywordRelevanceScorer(kw, 0.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new KeywordRelevanceScorer(kw, 0.1, -1.0));
    }

    @Test
    void constructor_rejectsNonPositiveOccurrenceCap(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["x"],"secondary":["y"]}
                """);
        assertThrows(IllegalArgumentException.class, () -> new KeywordRelevanceScorer(kw, 0.1, 0.1, 0));
    }

    private static Path writeKeywords(Path dir, String json) throws Exception {
        Path p = dir.resolve("k.json");
        Files.writeString(p, json);
        return p;
    }
}

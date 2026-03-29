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
        Path kw = writeKeywords(dir, """
                {"primary":["a","b","c","d","f"],"secondary":["x"]}
                """);
        KeywordRelevanceScorer s = new KeywordRelevanceScorer(kw, 0.3, 0.2);
        String hay = "a b c d f and x";
        double score = s.compute(hay, hay, hay);
        assertEquals(5 * 0.3 + 0.2, score, 1e-9);
        assertTrue(score > 1.0);
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
        assertEquals(0.5, t.maxKeywordScore(0.3, 0.2), 1e-9);
    }

    @Test
    void constructor_rejectsNonPositiveWeights(@TempDir Path dir) throws Exception {
        Path kw = writeKeywords(dir, """
                {"primary":["x"],"secondary":["y"]}
                """);
        assertThrows(IllegalArgumentException.class, () -> new KeywordRelevanceScorer(kw, 0.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new KeywordRelevanceScorer(kw, 0.1, -1.0));
    }

    private static Path writeKeywords(Path dir, String json) throws Exception {
        Path p = dir.resolve("k.json");
        Files.writeString(p, json);
        return p;
    }
}

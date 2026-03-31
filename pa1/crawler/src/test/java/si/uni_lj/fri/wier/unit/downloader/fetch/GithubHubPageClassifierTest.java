package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.GithubHubPageClassifier;

class GithubHubPageClassifierTest {

    @Test
    void matches_topicsRootAndSubpaths() {
        assertTrue(GithubHubPageClassifier.matches("https://github.com/topics"));
        assertTrue(GithubHubPageClassifier.matches("https://github.com/topics/image-segmentation"));
        assertTrue(GithubHubPageClassifier.matches("https://www.github.com/topics/ml"));
    }

    @Test
    void matches_singleSegmentProfile() {
        assertTrue(GithubHubPageClassifier.matches("https://github.com/imgly"));
        assertTrue(GithubHubPageClassifier.matches("https://github.com/rohitmenon86"));
        assertTrue(GithubHubPageClassifier.matches("https://www.github.com/some-user/"));
    }

    @Test
    void notMatches_repoRoot_twoSegments() {
        assertFalse(GithubHubPageClassifier.matches("https://github.com/catalyst-team/catalyst"));
        assertFalse(GithubHubPageClassifier.matches("https://github.com/org/repo/"));
    }

    @Test
    void notMatches_reservedSingleSegment() {
        assertFalse(GithubHubPageClassifier.matches("https://github.com/explore"));
        assertFalse(GithubHubPageClassifier.matches("https://github.com/login"));
    }

    @Test
    void notMatches_nonGitHubHost() {
        assertFalse(GithubHubPageClassifier.matches("https://example.com/topics/foo"));
    }
}

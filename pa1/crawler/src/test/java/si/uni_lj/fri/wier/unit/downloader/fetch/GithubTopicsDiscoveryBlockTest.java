package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.GithubTopicsDiscoveryBlock;

class GithubTopicsDiscoveryBlockTest {

    @Test
    void blocks_githubCom_topicsPaths() {
        assertTrue(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/topics"));
        assertTrue(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/topics/"));
        assertTrue(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/topics/foo"));
        assertTrue(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/topics/foo?page=2"));
    }

    @Test
    void allows_githubCom_repos_and_other_hosts() {
        assertFalse(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/org/repo"));
        assertFalse(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://github.com/topicsextra"));
        assertFalse(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://example.com/topics/foo"));
        assertFalse(GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl("https://api.github.com/topics/foo"));
    }

    @Test
    void requiresNonNull() {
        assertThrows(NullPointerException.class, () -> GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl(null));
    }
}

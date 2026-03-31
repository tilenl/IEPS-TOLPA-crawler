package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.GithubRepoSubpathDiscoveryBlock;

class GithubRepoSubpathDiscoveryBlockTest {

    private static final Set<String> DEFAULTS =
            Set.copyOf(GithubRepoSubpathDiscoveryBlock.DEFAULT_DENY_REPO_SUBPATHS);

    @Test
    void blocks_blob_issues_pull_variants_on_githubCom() {
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/org/repo/blob/main/README.md", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/qubvel/segmentation_models/issues", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/org/repo/issues/42", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/reachsumit/deep-unet-for-satellite-image-segmentation/labels", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/frankkramer-lab/MIScnn/discussions", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/reachsumit/deep-unet-for-satellite-image-segmentation/pulls", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/reachsumit/deep-unet-for-satellite-image-segmentation/pull/19", DEFAULTS));
    }

    @Test
    void blocks_wwwGithubCom_caseInsensitiveSegment() {
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://www.github.com/ORG/REPO/BLOB/x", DEFAULTS));
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://WWW.GITHUB.COM/o/r/issues", DEFAULTS));
    }

    @Test
    void allows_repo_root_tree_and_other_hosts() {
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/org/repo", DEFAULTS));
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/org/repo/tree/main", DEFAULTS));
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/topics/foo", DEFAULTS));
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://example.com/org/repo/issues", DEFAULTS));
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://api.github.com/repos/o/r/issues", DEFAULTS));
    }

    @Test
    void emptyDenySet_neverBlocks() {
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/org/repo/issues", Set.of()));
    }

    @Test
    void customDenySet_blocksOnlyListed() {
        Set<String> onlyWiki = Set.of("wiki");
        assertTrue(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/a/b/wiki", onlyWiki));
        assertFalse(GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                "https://github.com/a/b/issues", onlyWiki));
    }

    @Test
    void requiresNonNull() {
        assertThrows(
                NullPointerException.class,
                () -> GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(null, DEFAULTS));
        assertThrows(
                NullPointerException.class,
                () -> GithubRepoSubpathDiscoveryBlock.isBlockedGithubRepoSubpathDiscoveryUrl(
                        "https://github.com/o/r/issues", null));
    }
}

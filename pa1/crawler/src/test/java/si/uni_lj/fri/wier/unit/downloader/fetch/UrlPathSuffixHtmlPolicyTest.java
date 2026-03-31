package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.UrlPathSuffixHtmlPolicy;

class UrlPathSuffixHtmlPolicyTest {

    private static final List<String> DEFAULT_DENY = UrlPathSuffixHtmlPolicy.DEFAULT_DENY_PATH_POSTFIXES;

    @Test
    void pathSuffix_githubBlobPy() {
        assertEquals(
                "py",
                UrlPathSuffixHtmlPolicy.pathSuffixAfterLastDot(
                        "https://github.com/pytorch/fairseq/blob/master/fairseq/optim/adafactor.py"));
    }

    @Test
    void pathSuffix_repoNamePytorch_notPy() {
        assertEquals(
                "pytorch",
                UrlPathSuffixHtmlPolicy.pathSuffixAfterLastDot(
                        "https://github.com/qubvel-org/segmentation_models.pytorch"));
    }

    @Test
    void pathSuffix_md() {
        assertEquals(
                "md",
                UrlPathSuffixHtmlPolicy.pathSuffixAfterLastDot(
                        "https://github.com/org/repo/blob/main/README.md"));
    }

    @Test
    void pathSuffix_noExtension() {
        assertNull(UrlPathSuffixHtmlPolicy.pathSuffixAfterLastDot("https://github.com/topics/segmentation"));
    }

    @Test
    void pathSuffix_versionTagSingleCharAfterDot() {
        assertNull(UrlPathSuffixHtmlPolicy.pathSuffixAfterLastDot("https://example.com/releases/v1.0"));
    }

    @Test
    void shouldForceBinary_pyOnDefaultDeny() {
        assertTrue(
                UrlPathSuffixHtmlPolicy.shouldForceBinary(
                        "https://github.com/x/y/blob/main/a.py", DEFAULT_DENY));
    }

    @Test
    void shouldForceBinary_yamlOnDefaultDeny() {
        assertTrue(
                UrlPathSuffixHtmlPolicy.shouldForceBinary(
                        "https://github.com/x/y/blob/main/c.yaml", DEFAULT_DENY));
    }

    @Test
    void shouldForceBinary_pytorchRepoUrl_notForced() {
        assertFalse(
                UrlPathSuffixHtmlPolicy.shouldForceBinary(
                        "https://github.com/qubvel-org/segmentation_models.pytorch", DEFAULT_DENY));
    }

    @Test
    void shouldForceBinary_mdNotOnDenylist() {
        assertFalse(
                UrlPathSuffixHtmlPolicy.shouldForceBinary(
                        "https://github.com/x/y/blob/main/a.md", DEFAULT_DENY));
    }

    @Test
    void shouldForceBinary_noSuffix() {
        assertFalse(
                UrlPathSuffixHtmlPolicy.shouldForceBinary("https://example.com/page", DEFAULT_DENY));
    }

    @Test
    void shouldForceBinary_emptyDenylist_never() {
        assertFalse(
                UrlPathSuffixHtmlPolicy.shouldForceBinary(
                        "https://github.com/x/y/blob/main/a.py", List.of()));
    }
}

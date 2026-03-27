package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;

class HostKeysTest {

    @Test
    void domainKey_normalizesHost() {
        assertEquals("example.com", HostKeys.domainKey("https://Example.COM/path"));
    }

    @Test
    void domainKey_rejectsWithoutHost() {
        assertThrows(IllegalArgumentException.class, () -> HostKeys.domainKey("about:blank"));
    }

    @Test
    void isGitHubHost() {
        assertTrue(HostKeys.isGitHubHost("github.com"));
        assertTrue(HostKeys.isGitHubHost("api.github.com"));
        assertFalse(HostKeys.isGitHubHost("example.com"));
    }
}

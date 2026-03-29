package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.CrawlScope;
import si.uni_lj.fri.wier.config.CrawlScopes;

class CrawlScopesTest {

    @Test
    void parse_blank_defaultsToGithub() {
        assertEquals(CrawlScope.GITHUB, CrawlScopes.parseCrawlScope(null));
        assertEquals(CrawlScope.GITHUB, CrawlScopes.parseCrawlScope(""));
        assertEquals(CrawlScope.GITHUB, CrawlScopes.parseCrawlScope("   "));
    }

    @Test
    void parse_github_caseInsensitive() {
        assertEquals(CrawlScope.GITHUB, CrawlScopes.parseCrawlScope("github"));
        assertEquals(CrawlScope.GITHUB, CrawlScopes.parseCrawlScope(" GiThUb "));
    }

    @Test
    void parse_unknown_throws() {
        assertThrows(IllegalArgumentException.class, () -> CrawlScopes.parseCrawlScope("ALL"));
    }

    @Test
    void hostMatches_githubScope() {
        assertTrue(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "github.com"));
        assertTrue(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "api.github.com"));
        assertFalse(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "example.com"));
        assertFalse(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "github.io"));
        assertFalse(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "raw.githubusercontent.com"));
        assertFalse(CrawlScopes.hostMatchesCrawlScope(CrawlScope.GITHUB, "127.0.0.1"));
    }
}

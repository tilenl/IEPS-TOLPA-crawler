/*
 * TS-09 / TS-01: deterministic SHA-256 hex for content deduplication.
 *
 * Change log: 2026-03 — moved from unit.contracts for TS-09 directory mapping.
 */

package si.uni_lj.fri.wier.unit.downloader.dedup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl;

class ContentHasherImplUnitTest {

    @Test
    void sha256_is_deterministic_for_same_input() {
        ContentHasherImpl hasher = new ContentHasherImpl();
        String first = hasher.sha256("<html>same</html>");
        String second = hasher.sha256("<html>same</html>");
        assertEquals(first, second);
    }

    @Test
    void sha256_changes_when_input_changes() {
        ContentHasherImpl hasher = new ContentHasherImpl();
        String first = hasher.sha256("<html>one</html>");
        String second = hasher.sha256("<html>two</html>");
        assertNotEquals(first, second);
    }

    @Test
    void sha256_knownEmptyString_vector() {
        ContentHasherImpl hasher = new ContentHasherImpl();
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hasher.sha256(""));
    }
}

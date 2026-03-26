package si.uni_lj.fri.wier.unit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl;

class ContentHasherContractTest {

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
}

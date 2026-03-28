package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.downloader.fetch.ManualHttpRedirects;

class ManualHttpRedirectsTest {

    @Test
    void isRedirect_recognizesStandardCodes() {
        assertTrue(ManualHttpRedirects.isRedirect(301));
        assertTrue(ManualHttpRedirects.isRedirect(302));
        assertFalse(ManualHttpRedirects.isRedirect(200));
        assertFalse(ManualHttpRedirects.isRedirect(404));
    }

    @Test
    void resolveLocation_relative() {
        assertEquals(
                "http://a.example/b/c",
                ManualHttpRedirects.resolveLocation("http://a.example/x/y", "/b/c"));
    }

    @Test
    void resolveLocation_absolute() {
        assertEquals(
                "http://other.example/robots.txt",
                ManualHttpRedirects.resolveLocation(
                        "http://a.example/robots.txt", "http://other.example/robots.txt"));
    }

    @Test
    void resolveLocation_invalidThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ManualHttpRedirects.resolveLocation("http://[bad-host", "/x"));
    }
}

package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap;
import si.uni_lj.fri.wier.config.ConfigurationBootstrap.CliHelpRequestedException;

class ConfigurationBootstrapTest {

    @Test
    void applyEnvironmentOverlay_envOverridesProperty() {
        Properties p = new Properties();
        p.setProperty("crawler.nCrawlers", "1");
        // crawler.nCrawlers → CRAWLER_NCRAWLERS (no extra underscore: single segment after "crawler.")
        ConfigurationBootstrap.applyEnvironmentOverlay(
                p, name -> "CRAWLER_NCRAWLERS".equals(name) ? "9" : null);
        assertEquals("9", p.getProperty("crawler.nCrawlers"));
    }

    @Test
    void applyCli_setsNCrawlers() {
        Properties p = new Properties();
        assertTrue(!ConfigurationBootstrap.applyCli(new String[] {"--n-crawlers", "6"}, p));
        assertEquals("6", p.getProperty("crawler.nCrawlers"));
    }

    @Test
    void applyCli_helpRequestsExit() {
        Properties p = new Properties();
        assertTrue(ConfigurationBootstrap.applyCli(new String[] {"--help"}, p));
    }

    @Test
    void applyCli_unknownFlagThrows() {
        Properties p = new Properties();
        assertThrows(IllegalArgumentException.class, () -> ConfigurationBootstrap.applyCli(new String[] {"--x"}, p));
    }

    @Test
    void resolveEffectiveProperties_cliBeatsEnvAndFile(@TempDir Path dir) throws Exception {
        writeProps(
                dir,
                "application.properties",
                """
                crawler.profile=
                crawler.nCrawlers=1
                """);
        ClassLoader cl = isolatedLoader(dir);
        Function<String, String> env =
                name -> {
                    if ("CRAWLER_NCRAWLERS".equals(name)) {
                        return "2";
                    }
                    return null;
                };
        Properties eff =
                ConfigurationBootstrap.resolveEffectiveProperties(
                        cl, new String[] {"--n-crawlers", "3"}, env);
        assertEquals("3", eff.getProperty("crawler.nCrawlers"));
    }

    @Test
    void resolveEffectiveProperties_profileOverlaysBase(@TempDir Path dir) throws Exception {
        writeProps(
                dir,
                "application.properties",
                """
                crawler.profile=dev
                crawler.nCrawlers=
                """);
        writeProps(
                dir,
                "application-dev.properties",
                """
                crawler.nCrawlers=77
                """);
        ClassLoader cl = isolatedLoader(dir);
        Properties eff = ConfigurationBootstrap.resolveEffectiveProperties(cl, new String[0], k -> null);
        assertEquals("77", eff.getProperty("crawler.nCrawlers"));
    }

    @Test
    void resolveEffectiveProperties_helpThrowsCliHelpRequested(@TempDir Path dir) throws Exception {
        writeProps(dir, "application.properties", "crawler.profile=\n");
        ClassLoader cl = isolatedLoader(dir);
        assertThrows(
                CliHelpRequestedException.class,
                () -> ConfigurationBootstrap.resolveEffectiveProperties(cl, new String[] {"--help"}, k -> null));
    }

    private static void writeProps(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    /**
     * Parent {@code null} so {@code application.properties} is resolved only from {@code dir}, not from the
     * test runtime classpath (which also contains {@code application.properties}).
     */
    private static ClassLoader isolatedLoader(Path dir) throws IOException {
        URL url = dir.toUri().toURL();
        return new URLClassLoader(new URL[] {url}, null);
    }
}

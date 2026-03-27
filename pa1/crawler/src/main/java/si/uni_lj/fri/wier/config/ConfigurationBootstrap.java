/*
 * TS-13 configuration precedence: classpath application.properties → optional application-<profile>.properties
 * (profile name taken from base file only) → environment variables → CLI arguments.
 *
 * Callers: si.uni_lj.fri.wier.cli.Main. Tests use overloads with injectable env lookup.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Builds the effective {@link Properties} stack before {@link RuntimeConfig#fromProperties}.
 *
 * <p>Profile overlay: {@code crawler.profile} is read <strong>only</strong> from the base classpath
 * {@code application.properties}. Env/CLI may change {@code crawler.profile} later, but that does not load
 * another profile file (TS-13 locked decision).
 */
public final class ConfigurationBootstrap {

    private static final String BASE_RESOURCE = "application.properties";

    private ConfigurationBootstrap() {}

    /**
     * Resolves effective properties using {@link System#getenv()} for the environment layer.
     *
     * @param classLoader used to load classpath resources (typically {@code Main.class.getClassLoader()})
     * @param cliArgs raw CLI args (may be empty)
     */
    public static Properties resolveEffectiveProperties(ClassLoader classLoader, String[] cliArgs)
            throws IOException {
        return resolveEffectiveProperties(classLoader, cliArgs, System::getenv);
    }

    /**
     * Same as {@link #resolveEffectiveProperties(ClassLoader, String[])} with an injectable env lookup (tests).
     */
    public static Properties resolveEffectiveProperties(
            ClassLoader classLoader, String[] cliArgs, Function<String, String> envLookup) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(envLookup, "envLookup");
        String[] args = cliArgs == null ? new String[0] : cliArgs;

        Properties base = loadClasspathProperties(classLoader, BASE_RESOURCE);
        String profile =
                trimToNull(base.getProperty("crawler.profile"));
        Properties merged = new Properties();
        copyInto(merged, base);

        if (profile != null) {
            String profileResource = "application-" + profile + ".properties";
            try (InputStream in = classLoader.getResourceAsStream(profileResource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "crawler.profile=" + profile + " but classpath resource missing: " + profileResource);
                }
                Properties profileProps = new Properties();
                profileProps.load(in);
                copyInto(merged, profileProps);
            }
        }

        applyEnvironmentOverlay(merged, envLookup);
        boolean help = applyCli(args, merged);
        if (help) {
            throw new CliHelpRequestedException();
        }
        return merged;
    }

    private static void copyInto(Properties target, Properties source) {
        for (String name : source.stringPropertyNames()) {
            target.setProperty(name, source.getProperty(name));
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static Properties loadClasspathProperties(ClassLoader classLoader, String resourceName) throws IOException {
        try (InputStream in = classLoader.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + resourceName);
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    /**
     * For each known crawler key, if the corresponding {@code CRAWLER_*} env var is set and non-blank, set the
     * property (overrides file layers).
     */
    public static void applyEnvironmentOverlay(Properties target, Function<String, String> envLookup) {
        for (String key : CrawlerEnvironmentNames.OVERLAY_PROPERTY_KEYS) {
            String envName = CrawlerEnvironmentNames.propertyKeyToEnvName(key);
            String v = envLookup.apply(envName);
            if (v != null && !v.isBlank()) {
                target.setProperty(key, v.trim());
            }
        }
    }

    /**
     * Parses minimal CLI (TS-13): {@code --n-crawlers <int>}, {@code -h}, {@code --help}.
     *
     * @return {@code true} if help was requested (caller should print usage and exit)
     */
    public static boolean applyCli(String[] args, Properties target) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-h".equals(a) || "--help".equals(a)) {
                return true;
            }
            if ("--n-crawlers".equals(a)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--n-crawlers requires a value");
                }
                target.setProperty("crawler.nCrawlers", args[++i].trim());
                continue;
            }
            if (a.startsWith("-")) {
                throw new IllegalArgumentException("Unknown argument: " + a + " (try --help)");
            }
            throw new IllegalArgumentException("Unexpected token: " + a);
        }
        return false;
    }

    /** Thrown when {@code -h} / {@code --help} is parsed so Main can print usage and exit successfully. */
    public static final class CliHelpRequestedException extends RuntimeException {

        public CliHelpRequestedException() {
            super("CLI help requested");
        }
    }

    /** Short usage line for {@link si.uni_lj.fri.wier.cli.Main}. */
    public static String usageLine() {
        return "usage: ieps-tolpa [--n-crawlers N] [--help|-h]";
    }
}

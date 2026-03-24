package si.uni_lj.fri.wier.cli;

import java.io.InputStream;
import java.util.Properties;
import si.uni_lj.fri.wier.app.PreferentialCrawler;
import si.uni_lj.fri.wier.config.RuntimeConfig;

public final class Main {

    public static void main(String[] args) throws Exception {
        Properties props = loadClasspathProperties("application.properties");
        RuntimeConfig config = RuntimeConfig.fromProperties(props, Runtime.getRuntime().availableProcessors());
        new PreferentialCrawler(config).preflightAndLogEffectiveConfig();
    }

    private static Properties loadClasspathProperties(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + name);
            }
            p.load(in);
        }
        return p;
    }
}

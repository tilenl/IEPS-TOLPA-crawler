/*
 * Wraps a {@link javax.sql.DataSource} to count open JDBC connections for TS-15 pool utilization (checkout-based).
 *
 * Callers: {@link si.uni_lj.fri.wier.cli.Main}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.storage.postgres;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import javax.sql.DataSource;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

/**
 * Proxies {@link DataSource#getConnection()} variants so each returned {@link Connection} decrements the checkout
 * counter on {@link Connection#close()}. PGSimpleDataSource is not a true pool, but the same pattern applies when a
 * pool is swapped in later.
 */
public final class CountingDataSource {

    private CountingDataSource() {}

    /**
     * @return {@code delegate} when {@code metrics} is null; otherwise a {@link DataSource} proxy
     */
    public static DataSource wrap(DataSource delegate, CrawlerMetrics metrics) {
        if (metrics == null) {
            return delegate;
        }
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if ("getConnection".equals(method.getName())) {
                                Connection raw = (Connection) method.invoke(delegate, args);
                                metrics.onDbConnectionCheckedOut();
                                return proxyConnection(raw, metrics);
                            }
                            return method.invoke(delegate, args);
                        });
    }

    private static Connection proxyConnection(Connection raw, CrawlerMetrics metrics) {
        return (Connection)
                Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                                try {
                                    return method.invoke(raw, args);
                                } finally {
                                    metrics.onDbConnectionReturned();
                                }
                            }
                            return method.invoke(raw, args);
                        });
    }
}

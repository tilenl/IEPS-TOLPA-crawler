/*
 * TS-06 persistence for crawldb.site robots and sitemap text.
 *
 * Callers: Main wires {@link si.uni_lj.fri.wier.contracts.RobotsSiteMetadataSink} to PolitenessGate; tests use
 * Testcontainers datasource directly.
 *
 * Assumptions: {@code domain} matches the lower-case registry host key used by PolitenessGate. If no row exists,
 * an INSERT creates a site row so metadata is not dropped on first crawl (pages may link before explicit
 * ensureSite in some flows).
 *
 * Created: 2026-03 for TS-06.
 */

package si.uni_lj.fri.wier.storage.postgres.repositories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import si.uni_lj.fri.wier.contracts.RobotsSiteMetadataSink;

/**
 * Updates {@code crawldb.site.robots_content} and {@code sitemap_content} after robots.txt loads.
 */
public final class SiteRepository implements RobotsSiteMetadataSink {

    private final DataSource dataSource;

    public SiteRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Updates the first matching site row; inserts a new row when no site exists for the domain.
     *
     * @param domain lower-case host key
     * @param robotsContent raw robots body (UTF-8 text)
     * @param sitemapContent newline-separated sitemap URLs, may be empty
     */
    @Override
    public void save(String domain, String robotsContent, String sitemapContent) {
        Objects.requireNonNull(domain, "domain");
        String robots = robotsContent != null ? robotsContent : "";
        String sitemaps = sitemapContent != null ? sitemapContent : "";
        final String updateSql =
                """
                UPDATE crawldb.site
                SET robots_content = ?, sitemap_content = ?
                WHERE domain = ?
                """;
        final String insertSql =
                """
                INSERT INTO crawldb.site (domain, robots_content, sitemap_content)
                VALUES (?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setString(1, robots);
                statement.setString(2, sitemaps);
                statement.setString(3, domain);
                int updated = statement.executeUpdate();
                if (updated > 0) {
                    return;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                statement.setString(1, domain);
                statement.setString(2, robots);
                statement.setString(3, sitemaps);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist robots metadata domain=" + domain, e);
        }
    }

    /**
     * Repository-shaped alias for callers that prefer a non-functional name (TS-06 spec wording).
     */
    public void updateRobotsAndSitemap(String domain, String robotsContent, String sitemapContent) {
        save(domain, robotsContent, sitemapContent);
    }
}

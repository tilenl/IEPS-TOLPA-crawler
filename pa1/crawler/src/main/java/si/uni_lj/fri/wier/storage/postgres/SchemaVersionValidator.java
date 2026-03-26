package si.uni_lj.fri.wier.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import si.uni_lj.fri.wier.config.ConfigRemediation;

/**
 * Validates DB connectivity and schema version at startup (TS-11 / TS-15).
 *
 * <p>This validator performs a read-only check against {@code crawldb.schema_version} and intentionally
 * does not execute any DDL or migration SQL.
 */
public final class SchemaVersionValidator {
    private static final String READ_SCHEMA_VERSION_SQL =
            "SELECT version FROM crawldb.schema_version WHERE id = 1";

    private final DataSource dataSource;

    public SchemaVersionValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Validates that DB connectivity works and the singleton schema version matches configuration.
     *
     * @param expectedVersionText expected schema version from runtime configuration
     * @throws IllegalArgumentException when configured expected version is not a positive integer
     * @throws IllegalStateException when DB is unreachable, schema row is missing, or version mismatches
     */
    public void validateExpectedVersion(String expectedVersionText) {
        int expectedVersion = parseExpectedVersion(expectedVersionText);
        ConfigRemediation.Remediation remediation = ConfigRemediation.schemaVersionMismatch();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(READ_SCHEMA_VERSION_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException(
                        "Schema version validation failed: configKey="
                                + remediation.configKey()
                                + " expectedVersion="
                                + expectedVersion
                                + " dbVersion=<missing>"
                                + " remediationHint="
                                + remediation.remediationHint());
            }
            int dbVersion = resultSet.getInt(1);
            if (dbVersion != expectedVersion) {
                throw new IllegalStateException(
                        "Schema version mismatch: configKey="
                                + remediation.configKey()
                                + " expectedVersion="
                                + expectedVersion
                                + " dbVersion="
                                + dbVersion
                                + " remediationHint="
                                + remediation.remediationHint());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Schema/version connectivity check failed: configKey="
                            + remediation.configKey()
                            + " expectedVersion="
                            + expectedVersion
                            + " dbVersion=<unknown>"
                            + " remediationHint="
                            + remediation.remediationHint(),
                    exception);
        }
    }

    private static int parseExpectedVersion(String expectedVersionText) {
        try {
            int value = Integer.parseInt(expectedVersionText);
            if (value < 1) {
                throw new NumberFormatException("version must be >= 1");
            }
            return value;
        } catch (NumberFormatException exception) {
            ConfigRemediation.Remediation remediation =
                    ConfigRemediation.invalidConfigValue("crawler.db.expectedSchemaVersion", "integer >= 1");
            throw new IllegalArgumentException(
                    "Invalid configuration: crawler.db.expectedSchemaVersion (integer >= 1). remediationHint="
                            + remediation.remediationHint(),
                    exception);
        }
    }
}

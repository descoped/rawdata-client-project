package io.descoped.rawdata.provider.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.descoped.rawdata.api.RawdataClient;
import io.descoped.rawdata.api.RawdataClientInitializer;
import io.descoped.rawdata.api.RawdataException;
import io.descoped.rawdata.api.spi.RawdataLimits;
import io.descoped.service.provider.api.ProviderName;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code postgres} provider: topics in PostgreSQL 18. Configuration:
 * <table>
 *     <caption>Configuration keys</caption>
 *     <tr><th>Key</th><th>Default</th></tr>
 *     <tr><td>{@code postgres.driver.host}</td><td>{@code localhost}</td></tr>
 *     <tr><td>{@code postgres.driver.port}</td><td>{@code 5432}</td></tr>
 *     <tr><td>{@code postgres.driver.user}</td><td>required</td></tr>
 *     <tr><td>{@code postgres.driver.password}</td><td>required</td></tr>
 *     <tr><td>{@code postgres.driver.database}</td><td>required</td></tr>
 *     <tr><td>{@code postgres.driver.max-pool-size}</td><td>{@code 10}</td></tr>
 *     <tr><td>{@code postgres.schema}</td><td>{@code public}: the schema of the client's topics, an unquoted
 *     identifier (a-z, 0-9, _); one schema per class of data keeps topics of different sensitivity under different
 *     grants. It must exist.</td></tr>
 *     <tr><td>{@code rawdata.limits.max-position-bytes}, {@code rawdata.limits.max-publish-bytes}</td><td>the limits
 *     of a publish, as in every provider: {@link RawdataLimits}</td></tr>
 *     <tr><td>{@code rawdata.postgres.consumer.prefetch-size}</td><td>{@code 100}: messages per fetch</td></tr>
 *     <tr><td>{@code rawdata.postgres.consumer.prefetch-poll-interval-when-empty}</td><td>{@code 250}: milliseconds
 *     between polls of a topic that has nothing new</td></tr>
 *     <tr><td>{@code rawdata.postgres.consumer.wake-on-publish}</td><td>{@code true}: a publish wakes the client's
 *     waiting consumers of the topic, which read at once instead of at the next poll</td></tr>
 *     <tr><td>{@code rawdata.postgres.publish.lock-timeout-ms}</td><td>none: how long a publish waits for the topic's
 *     lock, or any other lock it needs, before it fails, writing nothing; without it, it waits as long as the holder
 *     does</td></tr>
 *     <tr><td>{@code postgres.driver.tcp-keep-alive}</td><td>{@code false}: TCP keep-alive on the connections, so a
 *     server that vanished is noticed by the operating system</td></tr>
 *     <tr><td>{@code postgres.driver.ssl-mode}</td><td>pgjdbc's default, {@code prefer}: {@code disable},
 *     {@code allow}, {@code prefer}, {@code require}, {@code verify-ca} or {@code verify-full}</td></tr>
 *     <tr><td>{@code postgres.driver.ssl-root-cert}</td><td>none: a file of the certificates that sign the server's;
 *     only with {@code verify-ca} or {@code verify-full}, refused with any other mode</td></tr>
 * </table>
 * Each topic has tables of its own, created at its first write in one transaction; the schema's registry of topics is
 * created when missing. Nothing is ever dropped. Creating needs {@code CREATE} on the schema; reading needs only
 * {@code USAGE} on it and {@code SELECT} on the tables. Connections are READ COMMITTED whatever the database's default, and show as
 * application {@code rawdata-postgres} in {@code pg_stat_activity}.
 */
@ProviderName("postgres")
public class PostgresRawdataClientInitializer implements RawdataClientInitializer {

    static final String HOST = "postgres.driver.host";
    static final String PORT = "postgres.driver.port";
    static final String USER = "postgres.driver.user";
    static final String PASSWORD = "postgres.driver.password";
    static final String DATABASE = "postgres.driver.database";
    static final String MAX_POOL_SIZE = "postgres.driver.max-pool-size";
    static final String SCHEMA = "postgres.schema";
    static final String PREFETCH_SIZE = "rawdata.postgres.consumer.prefetch-size";
    static final String POLL_INTERVAL = "rawdata.postgres.consumer.prefetch-poll-interval-when-empty";
    static final String WAKE_ON_PUBLISH = "rawdata.postgres.consumer.wake-on-publish";
    static final String LOCK_TIMEOUT = "rawdata.postgres.publish.lock-timeout-ms";
    static final String TCP_KEEP_ALIVE = "postgres.driver.tcp-keep-alive";
    static final String SSL_MODE = "postgres.driver.ssl-mode";
    static final String SSL_ROOT_CERT = "postgres.driver.ssl-root-cert";
    private static final Set<String> SSL_MODES = Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");
    static final String APPLICATION_NAME = "rawdata-postgres";

    /**
     * Creates the initializer; called by the service loader.
     */
    public PostgresRawdataClientInitializer() {
        // nothing to set up: the client is created in initialize
    }

    /**
     * The required keys; the service provider API refuses a configuration without them. The others have defaults.
     *
     * @return the user, password and database keys
     */
    @Override
    public Set<String> configurationKeys() {
        return Set.of(USER, PASSWORD, DATABASE);
    }

    @Override
    public RawdataClient initialize(Map<String, String> configuration) {
        RawdataLimits limits = RawdataLimits.of(configuration);
        String schemaName = configuration.get(SCHEMA);
        PostgresSchema schema = new PostgresSchema(schemaName == null || schemaName.isBlank() ? "public" : schemaName.trim());
        int prefetchSize = positive(configuration, PREFETCH_SIZE, 100);
        int pollIntervalMillis = positive(configuration, POLL_INTERVAL, 250);
        boolean wakeOnPublish = bool(configuration, WAKE_ON_PUBLISH, true);
        int lockTimeoutMillis = positive(configuration, LOCK_TIMEOUT, 0);

        HikariConfig config = new HikariConfig();
        config.setPoolName("rawdata-postgres");
        config.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        config.addDataSourceProperty("serverName", configuration.getOrDefault(HOST, "localhost"));
        config.addDataSourceProperty("portNumber", positive(configuration, PORT, 5432));
        config.addDataSourceProperty("user", required(configuration, USER));
        config.addDataSourceProperty("password", required(configuration, PASSWORD));
        config.addDataSourceProperty("databaseName", required(configuration, DATABASE));
        config.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
        if (bool(configuration, TCP_KEEP_ALIVE, false)) {
            config.addDataSourceProperty("tcpKeepAlive", true); // pgjdbc's default is off
        }
        tls(configuration, config);
        config.setAutoCommit(false);
        // The commit order of ids relies on it: after the topic lock, the last id must be read with a fresh snapshot.
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setMaximumPoolSize(positive(configuration, MAX_POOL_SIZE, 10));

        HikariDataSource dataSource;
        try {
            dataSource = new HikariDataSource(config);
        } catch (HikariPool.PoolInitializationException e) {
            // the database cannot be reached or does not exist: a store failure, as in every provider
            throw new RawdataException("PostgreSQL: " + e.getMessage(), e);
        }
        try {
            schema.create(new Jdbc(dataSource, () -> false));
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new PostgresRawdataClient(dataSource, schema, limits, prefetchSize, pollIntervalMillis, wakeOnPublish, lockTimeoutMillis);
    }

    private static String required(Map<String, String> configuration, String key) {
        String value = configuration.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration '" + key + "'");
        }
        return value;
    }

    /**
     * The TLS settings: a mode pgjdbc knows, in any case, and a root certificate only where it is used.
     */
    private static void tls(Map<String, String> configuration, HikariConfig config) {
        String sslMode = configuration.get(SSL_MODE);
        String mode = sslMode == null || sslMode.isBlank() ? null : sslMode.trim().toLowerCase(Locale.ROOT);
        if (mode != null) {
            if (!SSL_MODES.contains(mode)) {
                throw new IllegalArgumentException("Configuration '" + SSL_MODE + "' must be one of " + new TreeSet<>(SSL_MODES) + ": " + sslMode);
            }
            config.addDataSourceProperty("sslMode", mode);
        }
        String sslRootCert = configuration.get(SSL_ROOT_CERT);
        if (sslRootCert != null && !sslRootCert.isBlank()) {
            if (!"verify-ca".equals(mode) && !"verify-full".equals(mode)) { // otherwise no certificate is checked
                throw new IllegalArgumentException("Configuration '" + SSL_ROOT_CERT + "' needs '" + SSL_MODE
                        + "' verify-ca or verify-full; in mode " + (mode == null ? "prefer (the default)" : mode) + " it would be ignored");
            }
            config.addDataSourceProperty("sslRootCert", sslRootCert.trim());
        }
    }

    private static boolean bool(Map<String, String> configuration, String key, boolean defaultValue) {
        String value = configuration.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Configuration '" + key + "' must be true or false: " + value);
        };
    }

    private static int positive(Map<String, String> configuration, String key, int defaultValue) {
        String value = configuration.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value.trim());
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("Configuration '" + key + "' must be a positive number: " + value);
    }
}

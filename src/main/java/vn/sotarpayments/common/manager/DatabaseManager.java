package vn.sotarpayments.common.manager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseManager implements AutoCloseable {
    private static final int SQLITE_BUSY_TIMEOUT_MILLIS = 5_000;
    private static final int DEFAULT_MYSQL_PORT = 3306;
    private static final int DEFAULT_POSTGRESQL_PORT = 5432;
    private static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;
    private static final int DEFAULT_MINIMUM_IDLE = 1;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10_000L;
    private static final long DEFAULT_VALIDATION_TIMEOUT_MILLIS = 5_000L;
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 600_000L;
    private static final long DEFAULT_MAX_LIFETIME_MILLIS = 1_800_000L;
    private static final long DEFAULT_KEEPALIVE_MILLIS = 30_000L;
    private static final int DEFAULT_JDBC_CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int DEFAULT_JDBC_SOCKET_TIMEOUT_MILLIS = 10_000;

    private final SotarPayments plugin;
    private final Backend backend;
    private final HikariDataSource dataSource;
    private final Object schemaLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile boolean schemaReady;

    private enum Backend {
        SQLITE("SQLite", "org.sqlite.JDBC"),
        MYSQL("MySQL", "com.mysql.cj.jdbc.Driver"),
        MARIADB("MariaDB", "org.mariadb.jdbc.Driver"),
        POSTGRESQL("PostgreSQL", "org.postgresql.Driver");

        private final String displayName;
        private final String driverClassName;

        Backend(String displayName, String driverClassName) {
            this.displayName = displayName;
            this.driverClassName = driverClassName;
        }

        private boolean isRemote() {
            return this != SQLITE;
        }

        private static Backend fromConfig(String configured) {
            String normalized = configured == null ? "sqlite" : configured.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "mariadb", "maria" -> MARIADB;
                case "postgresql", "postgres", "pgsql" -> POSTGRESQL;
                default -> throw new IllegalArgumentException(
                        "Unsupported database.type '" + normalized
                                + "'. Use sqlite, mysql, mariadb, or postgresql."
                );
            };
        }
    }

    public record TransactionRecord(long id, String player, long amount, long time,
                                    PaymentChannel channel, String provider, String detail) {}

    public enum ClaimResult {
        CLAIMED,
        ALREADY_CLAIMED,
        DATABASE_ERROR
    }

    public static long getPeriodStartTime(String period) {
        String safePeriod = period == null ? "all" : period.trim().toLowerCase(Locale.ROOT);
        if (safePeriod.equals("all") || safePeriod.equals("lifetime") || safePeriod.equals("total")) {
            return 0L;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        switch (safePeriod) {
            case "today", "day", "daily" -> {
                return calendar.getTimeInMillis();
            }
            case "week", "weekly" -> {
                calendar.setFirstDayOfWeek(Calendar.MONDAY);
                calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
                return calendar.getTimeInMillis();
            }
            case "month", "monthly" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                return calendar.getTimeInMillis();
            }
            case "year", "yearly" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1);
                return calendar.getTimeInMillis();
            }
            default -> {
                return 0L;
            }
        }
    }

    public DatabaseManager(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backend = Backend.fromConfig(plugin.config().getString("database.type", "sqlite"));
        ensureDataFolder();

        HikariDataSource createdDataSource = null;
        try {
            createdDataSource = createDataSource();
            this.dataSource = createdDataSource;
            ensureSchema(true);
            plugin.getLogger().info("Database backend ready: " + backend.displayName + ".");
        } catch (RuntimeException | SQLException exception) {
            if (createdDataSource != null) {
                try {
                    createdDataSource.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            shutdownOwnedJdbcResources();
            throw new IllegalStateException(
                    "Could not initialize the configured " + backend.displayName
                            + " backend. SotarPayments will not fall back to another database.",
                    exception
            );
        }
    }

    private void ensureDataFolder() {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataFolder);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create plugin data folder: " + dataFolder, exception);
        }
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SotarPayments-" + backend.displayName);
        hikari.setDriverClassName(backend.driverClassName);
        hikari.setAutoCommit(true);
        hikari.setConnectionTimeout(readBoundedLong(
                "database.pool.connection-timeout-ms", DEFAULT_CONNECTION_TIMEOUT_MILLIS, 500L, 60_000L));

        long validationTimeout = readBoundedLong(
                "database.pool.validation-timeout-ms", DEFAULT_VALIDATION_TIMEOUT_MILLIS, 250L, 30_000L);
        if (validationTimeout >= hikari.getConnectionTimeout()) {
            validationTimeout = Math.max(250L, hikari.getConnectionTimeout() - 250L);
        }
        hikari.setValidationTimeout(validationTimeout);

        if (backend == Backend.SQLITE) {
            Path sqliteFile = resolveSqliteFile();
            hikari.setJdbcUrl("jdbc:sqlite:" + sqliteFile);
            hikari.setMaximumPoolSize(1);
            hikari.setMinimumIdle(1);
            hikari.setIdleTimeout(0L);
            hikari.setMaxLifetime(0L);
            hikari.setKeepaliveTime(0L);
            hikari.setInitializationFailTimeout(hikari.getConnectionTimeout());
            return new HikariDataSource(hikari);
        }

        configureRemoteDataSource(hikari);
        return new HikariDataSource(hikari);
    }

    private Path resolveSqliteFile() {
        String configured = plugin.config().getString("database.sqlite.file", "database.sqlite");
        String fileName = configured == null || configured.isBlank() ? "database.sqlite" : configured.trim();

        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = dataFolder.resolve(fileName).normalize();
        if (!resolved.startsWith(dataFolder) || resolved.equals(dataFolder)) {
            throw new IllegalArgumentException("database.sqlite.file must stay inside the SotarPayments data folder.");
        }

        Path parent = resolved.getParent();
        try {
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create the SQLite database directory.", exception);
        }
        return resolved;
    }

    private void configureRemoteDataSource(HikariConfig hikari) {
        String host = requireRemoteValue("database.remote.host");
        String databaseName = requireRemoteValue("database.remote.database");
        validateRemoteHost(host);
        validateDatabaseName(databaseName);

        int defaultPort = backend == Backend.POSTGRESQL ? DEFAULT_POSTGRESQL_PORT : DEFAULT_MYSQL_PORT;
        int configuredPort = plugin.config().getInt("database.remote.port", 0);
        int port = configuredPort == 0 ? defaultPort : configuredPort;
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("database.remote.port must be 0 (automatic) or between 1 and 65535.");
        }

        String username = plugin.config().getString("database.remote.username", "");
        String password = plugin.config().getString("database.remote.password", "");
        boolean useSsl = plugin.config().getBoolean("database.remote.use-ssl", false);
        boolean allowPublicKeyRetrieval = plugin.config().getBoolean(
                "database.remote.allow-public-key-retrieval", false);
        int jdbcConnectTimeout = readBoundedInt(
                "database.remote.connect-timeout-ms", DEFAULT_JDBC_CONNECT_TIMEOUT_MILLIS, 500, 60_000);
        int jdbcSocketTimeout = readBoundedInt(
                "database.remote.socket-timeout-ms", DEFAULT_JDBC_SOCKET_TIMEOUT_MILLIS, 1_000, 120_000);

        String address = formatRemoteAddress(host, port);
        String jdbcUrl;
        if (backend == Backend.MYSQL) {
            jdbcUrl = "jdbc:mysql://" + address + "/" + databaseName
                    + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true&tcpKeepAlive=true"
                    + "&connectTimeout=" + jdbcConnectTimeout + "&socketTimeout=" + jdbcSocketTimeout
                    + "&sslMode=" + (useSsl ? "REQUIRED" : "DISABLED")
                    + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval;
        } else if (backend == Backend.MARIADB) {
            jdbcUrl = "jdbc:mariadb://" + address + "/" + databaseName
                    + "?tcpKeepAlive=true"
                    + "&connectTimeout=" + jdbcConnectTimeout + "&socketTimeout=" + jdbcSocketTimeout
                    + "&sslMode=" + (useSsl ? "verify-full" : "disable");
        } else {
            int connectTimeoutSeconds = Math.max(1, (jdbcConnectTimeout + 999) / 1000);
            int socketTimeoutSeconds = Math.max(1, (jdbcSocketTimeout + 999) / 1000);
            jdbcUrl = "jdbc:postgresql://" + address + "/" + databaseName
                    + "?tcpKeepAlive=true"
                    + "&connectTimeout=" + connectTimeoutSeconds
                    + "&socketTimeout=" + socketTimeoutSeconds
                    + "&sslmode=" + (useSsl ? "require" : "disable");
        }

        int maximumPoolSize = readBoundedInt(
                "database.pool.maximum-pool-size", DEFAULT_MAXIMUM_POOL_SIZE, 1, 50);
        int minimumIdle = readBoundedInt(
                "database.pool.minimum-idle", DEFAULT_MINIMUM_IDLE, 0, maximumPoolSize);
        long idleTimeout = readBoundedLong(
                "database.pool.idle-timeout-ms", DEFAULT_IDLE_TIMEOUT_MILLIS, 10_000L, 3_600_000L);
        long maxLifetime = readBoundedLong(
                "database.pool.max-lifetime-ms", DEFAULT_MAX_LIFETIME_MILLIS, 30_000L, 86_400_000L);
        long keepalive = readBoundedLong(
                "database.pool.keepalive-time-ms", DEFAULT_KEEPALIVE_MILLIS, 0L, 3_600_000L);
        if (keepalive > 0L && (keepalive < 30_000L || keepalive >= maxLifetime)) {
            plugin.logWarning("database.pool.keepalive-time-ms is incompatible with max-lifetime-ms; keepalive disabled.");
            keepalive = 0L;
        }

        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(username == null ? "" : username);
        hikari.setPassword(password == null ? "" : password);
        hikari.setMaximumPoolSize(maximumPoolSize);
        hikari.setMinimumIdle(minimumIdle);
        hikari.setIdleTimeout(idleTimeout);
        hikari.setMaxLifetime(maxLifetime);
        hikari.setKeepaliveTime(keepalive);
        hikari.setInitializationFailTimeout(hikari.getConnectionTimeout());
    }

    private String requireRemoteValue(String path) {
        String value = plugin.config().getString(path, "");
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(path + " cannot be empty for " + backend.displayName + ".");
        }
        return value.trim();
    }

    private void validateRemoteHost(String host) {
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if (Character.isISOControl(character)
                    || Character.isWhitespace(character)
                    || character == '/' || character == '?' || character == '#'
                    || character == '&' || character == '=' || character == '@') {
                throw new IllegalArgumentException("database.remote.host contains an invalid character.");
            }
        }
    }

    private void validateDatabaseName(String databaseName) {
        if (!databaseName.matches("[A-Za-z0-9_$-]{1,64}")) {
            throw new IllegalArgumentException(
                    "database.remote.database may contain only letters, numbers, _, $, and - (maximum 64 characters)."
            );
        }
    }

    private String formatRemoteAddress(String host, int port) {
        String normalizedHost = host;
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            normalizedHost = "[" + host + "]";
        }
        return normalizedHost + ":" + port;
    }

    private int readBoundedInt(String path, int fallback, int minimum, int maximum) {
        int configured = plugin.config().getInt(path, fallback);
        int bounded = Math.max(minimum, Math.min(maximum, configured));
        if (bounded != configured) {
            plugin.logWarning(path + " is outside the supported range " + minimum + "-" + maximum
                    + "; using " + bounded + ".");
        }
        return bounded;
    }

    private long readBoundedLong(String path, long fallback, long minimum, long maximum) {
        long configured = plugin.config().getLong(path, fallback);
        long bounded = Math.max(minimum, Math.min(maximum, configured));
        if (bounded != configured) {
            plugin.logWarning(path + " is outside the supported range " + minimum + "-" + maximum
                    + "; using " + bounded + ".");
        }
        return bounded;
    }

    private Connection openConnection() throws SQLException {
        if (closed.get()) {
            throw new SQLException("Database pool is closed.");
        }

        Connection connection = dataSource.getConnection();
        if (backend == Backend.SQLITE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = " + SQLITE_BUSY_TIMEOUT_MILLIS);
                statement.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException exception) {
                connection.close();
                throw exception;
            }
        }
        return connection;
    }

    private void ensureSchema(boolean force) throws SQLException {
        if (!force && schemaReady) return;

        synchronized (schemaLock) {
            if (!force && schemaReady) return;

            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                if (backend == Backend.SQLITE) {
                    applyOptionalPragma(stmt, "PRAGMA journal_mode = WAL");
                    applyOptionalPragma(stmt, "PRAGMA synchronous = NORMAL");
                }

                createTransactionsTable(stmt);
                migrateTransactionsTable(conn);

                createSupportingTables(stmt);
                createIndexes(conn);

                schemaReady = true;
            } catch (SQLException e) {
                schemaReady = false;
                throw e;
            }
        }
    }

    private void applyOptionalPragma(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException ignored) {
            // Optional SQLite tuning must never prevent the plugin from starting.
        }
    }

    private void createTransactionsTable(Statement stmt) throws SQLException {
        if (backend == Backend.SQLITE) {
            stmt.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "player TEXT, " +
                    "amount INTEGER NOT NULL DEFAULT 0, " +
                    "time INTEGER NOT NULL DEFAULT 0, " +
                    "type TEXT NOT NULL DEFAULT 'LEGACY', " +
                    "provider TEXT NOT NULL DEFAULT '', " +
                    "detail TEXT NOT NULL DEFAULT ''" +
                    ")");
            return;
        }

        if (backend == Backend.POSTGRESQL) {
            stmt.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "player VARCHAR(64), " +
                    "amount BIGINT NOT NULL DEFAULT 0, " +
                    "time BIGINT NOT NULL DEFAULT 0, " +
                    "type VARCHAR(32) NOT NULL DEFAULT 'LEGACY', " +
                    "provider VARCHAR(255) NOT NULL DEFAULT '', " +
                    "detail VARCHAR(255) NOT NULL DEFAULT ''" +
                    ")");
            return;
        }

        stmt.execute("CREATE TABLE IF NOT EXISTS transactions (" +
                "id BIGINT NOT NULL AUTO_INCREMENT, " +
                "player VARCHAR(64), " +
                "amount BIGINT NOT NULL DEFAULT 0, " +
                "time BIGINT NOT NULL DEFAULT 0, " +
                "type VARCHAR(32) NOT NULL DEFAULT 'LEGACY', " +
                "provider VARCHAR(255) NOT NULL DEFAULT '', " +
                "detail VARCHAR(255) NOT NULL DEFAULT '', " +
                "PRIMARY KEY(id)" +
                ") ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
    }

    private void createSupportingTables(Statement stmt) throws SQLException {
        if (backend == Backend.SQLITE) {
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed (player TEXT, reward_id TEXT, period TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed_milestones (player TEXT, milestone TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed_server_milestones (" +
                    "player TEXT NOT NULL, milestone TEXT NOT NULL, PRIMARY KEY(player, milestone))");
            stmt.execute("CREATE TABLE IF NOT EXISTS reached_server_milestones (" +
                    "milestone TEXT PRIMARY KEY, reached_at INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS bossbar_preferences (" +
                    "player TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1)");
            return;
        }

        if (backend == Backend.POSTGRESQL) {
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed (" +
                    "player VARCHAR(64), reward_id VARCHAR(191), period VARCHAR(64))");
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed_milestones (" +
                    "player VARCHAR(64), milestone VARCHAR(64))");
            stmt.execute("CREATE TABLE IF NOT EXISTS claimed_server_milestones (" +
                    "player VARCHAR(64) NOT NULL, milestone VARCHAR(64) NOT NULL, " +
                    "PRIMARY KEY(player, milestone))");
            stmt.execute("CREATE TABLE IF NOT EXISTS reached_server_milestones (" +
                    "milestone VARCHAR(64) PRIMARY KEY, reached_at BIGINT NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS bossbar_preferences (" +
                    "player VARCHAR(64) PRIMARY KEY, enabled SMALLINT NOT NULL DEFAULT 1)");
            return;
        }

        String tableOptions = " ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        stmt.execute("CREATE TABLE IF NOT EXISTS claimed (" +
                "player VARCHAR(64), reward_id VARCHAR(191), period VARCHAR(64))" + tableOptions);
        stmt.execute("CREATE TABLE IF NOT EXISTS claimed_milestones (" +
                "player VARCHAR(64), milestone VARCHAR(64))" + tableOptions);
        stmt.execute("CREATE TABLE IF NOT EXISTS claimed_server_milestones (" +
                "player VARCHAR(64) NOT NULL, milestone VARCHAR(64) NOT NULL, " +
                "PRIMARY KEY(player, milestone))" + tableOptions);
        stmt.execute("CREATE TABLE IF NOT EXISTS reached_server_milestones (" +
                "milestone VARCHAR(64) NOT NULL, reached_at BIGINT NOT NULL, PRIMARY KEY(milestone))" + tableOptions);
        stmt.execute("CREATE TABLE IF NOT EXISTS bossbar_preferences (" +
                "player VARCHAR(64) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1, PRIMARY KEY(player))" + tableOptions);
    }

    private void createIndexes(Connection conn) throws SQLException {
        ensureIndex(conn, "transactions", "idx_transactions_player_time",
                "CREATE INDEX idx_transactions_player_time ON transactions(player, time)");
        if (backend == Backend.SQLITE) {
            ensureIndex(conn, "transactions", "idx_transactions_player_nocase_time",
                    "CREATE INDEX idx_transactions_player_nocase_time ON transactions(player COLLATE NOCASE, time)");
        }
        if (backend == Backend.POSTGRESQL) {
            ensureIndex(conn, "transactions", "idx_transactions_player_lower_time",
                    "CREATE INDEX idx_transactions_player_lower_time ON transactions(LOWER(player), time)");
        }
        ensureIndex(conn, "transactions", "idx_transactions_time",
                "CREATE INDEX idx_transactions_time ON transactions(time)");
        ensureIndex(conn, "transactions", "idx_transactions_type_time",
                "CREATE INDEX idx_transactions_type_time ON transactions(type, time)");
        ensureIndex(conn, "claimed", "idx_claimed_lookup",
                "CREATE INDEX idx_claimed_lookup ON claimed(player, reward_id, period)");
        ensureIndex(conn, "claimed_milestones", "idx_claimed_milestones_lookup",
                "CREATE INDEX idx_claimed_milestones_lookup ON claimed_milestones(player, milestone)");

        if (backend == Backend.SQLITE || backend == Backend.POSTGRESQL) {
            deduplicateLegacyMilestoneClaims(conn, "claimed_milestones");
        }
        String personalClaimIndex = switch (backend) {
            case SQLITE -> "ON claimed_milestones(player COLLATE NOCASE, milestone)";
            case POSTGRESQL -> "ON claimed_milestones(LOWER(player), milestone)";
            case MYSQL, MARIADB -> "ON claimed_milestones(player, milestone)";
        };
        ensureIndex(conn, "claimed_milestones", "uq_claimed_milestones_player_milestone_nocase",
                "CREATE UNIQUE INDEX uq_claimed_milestones_player_milestone_nocase " + personalClaimIndex);

        if (backend == Backend.SQLITE || backend == Backend.POSTGRESQL) {
            deduplicateLegacyMilestoneClaims(conn, "claimed_server_milestones");
            String serverClaimIndex = backend == Backend.SQLITE
                    ? "ON claimed_server_milestones(player COLLATE NOCASE, milestone)"
                    : "ON claimed_server_milestones(LOWER(player), milestone)";
            ensureIndex(conn, "claimed_server_milestones",
                    "uq_claimed_server_milestones_player_milestone_nocase",
                    "CREATE UNIQUE INDEX uq_claimed_server_milestones_player_milestone_nocase "
                            + serverClaimIndex);
        }
    }

    private void deduplicateLegacyMilestoneClaims(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (backend == Backend.SQLITE) {
                // Older SQLite versions only had a non-unique lookup index.
                stmt.executeUpdate("DELETE FROM " + table + " "
                        + "WHERE rowid NOT IN ("
                        + "SELECT MIN(rowid) FROM " + table
                        + " GROUP BY player COLLATE NOCASE, milestone"
                        + ")");
                return;
            }
            if (backend == Backend.POSTGRESQL) {
                stmt.executeUpdate("DELETE FROM " + table + " older USING " + table + " newer "
                        + "WHERE older.ctid < newer.ctid "
                        + "AND LOWER(older.player) = LOWER(newer.player) "
                        + "AND older.milestone = newer.milestone");
            }
        }
    }

    private void migrateTransactionsTable(Connection conn) throws SQLException {
        if (requiresTransactionTableRebuild(conn)) {
            rebuildTransactionsTable(conn);
            return;
        }

        String typeDefinition = backend == Backend.SQLITE
                ? "TEXT NOT NULL DEFAULT 'LEGACY'" : "VARCHAR(32) NOT NULL DEFAULT 'LEGACY'";
        String textDefinition = backend == Backend.SQLITE
                ? "TEXT NOT NULL DEFAULT ''" : "VARCHAR(255) NOT NULL DEFAULT ''";
        ensureColumn(conn, "transactions", "type", typeDefinition);
        ensureColumn(conn, "transactions", "provider", textDefinition);
        ensureColumn(conn, "transactions", "detail", textDefinition);
    }

    private boolean requiresTransactionTableRebuild(Connection conn) throws SQLException {
        return !hasColumn(conn, "transactions", "id")
                || !hasColumn(conn, "transactions", "player")
                || !hasColumn(conn, "transactions", "amount")
                || !hasColumn(conn, "transactions", "time");
    }

    private void rebuildTransactionsTable(Connection conn) throws SQLException {
        if (backend != Backend.SQLITE) {
            throw new SQLException("The remote transactions table is missing required legacy columns. "
                    + "Refusing an automatic destructive rebuild; repair or migrate it explicitly.");
        }

        String legacyTable = "transactions_legacy_" + System.currentTimeMillis();
        boolean hasPlayer = hasColumn(conn, "transactions", "player");
        boolean hasAmount = hasColumn(conn, "transactions", "amount");
        boolean hasTime = hasColumn(conn, "transactions", "time");
        boolean hasType = hasColumn(conn, "transactions", "type");
        boolean hasProvider = hasColumn(conn, "transactions", "provider");
        boolean hasDetail = hasColumn(conn, "transactions", "detail");

        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE transactions RENAME TO " + legacyTable);
            createTransactionsTable(stmt);

            String playerExpr = hasPlayer ? "player" : "''";
            String amountExpr = hasAmount ? "COALESCE(amount, 0)" : "0";
            String timeExpr = hasTime ? "COALESCE(time, 0)" : "0";
            String typeExpr = hasType ? "COALESCE(type, 'LEGACY')" : "'LEGACY'";
            String providerExpr = hasProvider ? "COALESCE(provider, '')" : "''";
            String detailExpr = hasDetail ? "COALESCE(detail, '')" : "''";

            stmt.execute("INSERT INTO transactions(player, amount, time, type, provider, detail) " +
                    "SELECT " + playerExpr + ", " + amountExpr + ", " + timeExpr + ", " +
                    typeExpr + ", " + providerExpr + ", " + detailExpr + " FROM " + legacyTable);
            stmt.execute("DROP TABLE " + legacyTable);
            conn.commit();
        } catch (SQLException exception) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    private void ensureColumn(Connection conn, String table, String column, String definition) throws SQLException {
        if (hasColumn(conn, table, column)) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData metadata = conn.getMetaData();
        try (ResultSet rs = metadata.getColumns(conn.getCatalog(), null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureIndex(Connection conn, String table, String indexName, String createSql) throws SQLException {
        if (hasIndex(conn, table, indexName)) return;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
        } catch (SQLException exception) {
            if (!hasIndex(conn, table, indexName)) throw exception;
        }
    }

    private boolean hasIndex(Connection conn, String table, String indexName) throws SQLException {
        DatabaseMetaData metadata = conn.getMetaData();
        try (ResultSet rs = metadata.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                String existing = rs.getString("INDEX_NAME");
                if (existing != null && indexName.equalsIgnoreCase(existing)) {
                    return true;
                }
            }
        }
        return false;
    }

    private <T> T executeSql(String action, T fallback, SqlSupplier<T> supplier) {
        try {
            ensureSchema(false);
            return supplier.get();
        } catch (SQLException first) {
            if (isSchemaProblem(first)) {
                try {
                    ensureSchema(true);
                    return supplier.get();
                } catch (SQLException retry) {
                    logSqlException(action, retry);
                    return fallback;
                }
            }

            logSqlException(action, first);
            return fallback;
        }
    }

    private void executeSql(String action, SqlRunnable runnable) {
        executeSql(action, null, () -> {
            runnable.run();
            return null;
        });
    }

    private boolean isSchemaProblem(SQLException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("no such table")
                        || normalized.contains("no such column")
                        || normalized.contains("missing database")
                        || normalized.contains("doesn't exist")
                        || normalized.contains("unknown column")) {
                    return true;
                }
            }

            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if ("42S02".equalsIgnoreCase(sqlState) || "42S22".equalsIgnoreCase(sqlState)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public long getRevenue(String period) {
        return getRevenueSince(getPeriodStartTime(period));
    }

    public long getRevenueSince(long startTime) {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE time >= ?")) {
                ps.setLong(1, Math.max(0L, startTime));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public void addTransaction(String p, long amt) {
        addTransaction(p, amt, PaymentChannel.LEGACY, "", "");
    }

    public void addTransaction(String p, long amt, PaymentChannel channel, String provider, String detail) {
        PaymentChannel safeChannel = channel == null ? PaymentChannel.LEGACY : channel;
        executeSql("database operation", () -> {
            try (Connection c = openConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO transactions(player, amount, time, type, provider, detail) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, p);
                ps.setLong(2, amt);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, safeChannel.storageKey());
                ps.setString(5, sanitize(provider));
                ps.setString(6, sanitize(detail));
                ps.executeUpdate();
            }
        });
    }

    private String sanitize(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > 240 ? cleaned.substring(0, 240) : cleaned;
    }

    public Map<String, Long> getTop(long startTime, int limit) {
        Map<String, Long> top = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : getTopListSince(startTime, Math.max(1, limit), 0)) {
            top.put(entry.getKey(), entry.getValue());
        }
        return top;
    }

    public List<Map<String, Object>> getRawHistory(String p) {
        return executeSql("database operation", new ArrayList<>(), () -> {
            List<Map<String, Object>> hist = new ArrayList<>();
            try (Connection c = openConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT amount, time, type, provider, detail FROM transactions WHERE "
                                 + playerPredicate("player") + " ORDER BY id DESC LIMIT 45")) {
                ps.setString(1, p);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("amount", rs.getLong("amount"));
                        data.put("time", rs.getLong("time"));
                        data.put("type", rs.getString("type"));
                        data.put("provider", rs.getString("provider"));
                        data.put("detail", rs.getString("detail"));
                        hist.add(data);
                    }
                }
            }
            return hist;
        });
    }

    public boolean hasClaimedMilestone(String player, String milestone) {
        return executeSql("database lookup", false, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM claimed_milestones WHERE "
                         + playerPredicate("player") + " AND milestone = ?")) {
                ps.setString(1, player);
                ps.setString(2, milestone);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void saveClaimMilestone(String player, String milestone) {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(insertIgnoreSql(
                         "claimed_milestones", "player, milestone", "?, ?"))) {
                ps.setString(1, player);
                ps.setString(2, milestone);
                ps.executeUpdate();
            }
        });
    }

    public ClaimResult reserveMilestoneClaim(String player, String milestone) {
        return reserveClaim("claimed_milestones", player, milestone);
    }

    public boolean releaseMilestoneClaim(String player, String milestone) {
        return releaseClaim("claimed_milestones", player, milestone);
    }

    public boolean hasClaimedServerMilestone(String player, String milestone) {
        return executeSql("database lookup", false, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM claimed_server_milestones WHERE "
                         + playerPredicate("player") + " AND milestone = ?")) {
                ps.setString(1, player);
                ps.setString(2, milestone);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void saveClaimServerMilestone(String player, String milestone) {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(insertIgnoreSql(
                         "claimed_server_milestones", "player, milestone", "?, ?"))) {
                ps.setString(1, player);
                ps.setString(2, milestone);
                ps.executeUpdate();
            }
        });
    }

    public ClaimResult reserveServerMilestoneClaim(String player, String milestone) {
        return reserveClaim("claimed_server_milestones", player, milestone);
    }

    public boolean releaseServerMilestoneClaim(String player, String milestone) {
        return releaseClaim("claimed_server_milestones", player, milestone);
    }

    private ClaimResult reserveClaim(String table, String player, String milestone) {
        if (player == null || player.isBlank() || milestone == null || milestone.isBlank()) {
            return ClaimResult.DATABASE_ERROR;
        }

        try {
            ensureSchema(false);
            return insertClaim(table, player, milestone);
        } catch (SQLException first) {
            if (isSchemaProblem(first)) {
                try {
                    ensureSchema(true);
                    return insertClaim(table, player, milestone);
                } catch (SQLException retry) {
                    logSqlException("claim reservation", retry);
                    return ClaimResult.DATABASE_ERROR;
                }
            }

            logSqlException("claim reservation", first);
            return ClaimResult.DATABASE_ERROR;
        }
    }

    private ClaimResult insertClaim(String table, String player, String milestone) throws SQLException {
        String sql = insertIgnoreSql(table, "player, milestone", "?, ?");
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player);
            ps.setString(2, milestone);
            return ps.executeUpdate() > 0 ? ClaimResult.CLAIMED : ClaimResult.ALREADY_CLAIMED;
        }
    }

    private boolean releaseClaim(String table, String player, String milestone) {
        if (player == null || milestone == null) return false;

        return executeSql("claim release", false, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM " + table + " WHERE " + playerPredicate("player") + " AND milestone = ?")) {
                ps.setString(1, player);
                ps.setString(2, milestone);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private String insertIgnoreSql(String table, String columns, String placeholders) {
        String statement = switch (backend) {
            case SQLITE -> "INSERT OR IGNORE INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
            case POSTGRESQL -> "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders
                    + ") ON CONFLICT DO NOTHING";
            case MYSQL, MARIADB -> "INSERT IGNORE INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
        };
        return statement;
    }

    public boolean hasReachedServerMilestone(String milestone) {
        return executeSql("database lookup", false, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM reached_server_milestones WHERE milestone = ?")) {
                ps.setString(1, milestone);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public long getReachedServerMilestoneAt(String milestone) {
        return executeSql("database lookup", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT reached_at FROM reached_server_milestones WHERE milestone = ?")) {
                ps.setString(1, milestone);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Math.max(0L, rs.getLong("reached_at")) : 0L;
                }
            }
        });
    }

    public void saveReachedServerMilestone(String milestone) {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(insertIgnoreSql(
                         "reached_server_milestones", "milestone, reached_at", "?, ?"))) {
                ps.setString(1, milestone);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    public Set<String> getDisabledBossBarPlayers() {
        return executeSql("database operation", new HashSet<>(), () -> {
            Set<String> players = new HashSet<>();
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT player FROM bossbar_preferences WHERE enabled = 0")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String player = normalizePlayerKey(rs.getString("player"));
                        if (!player.isBlank()) {
                            players.add(player);
                        }
                    }
                }
            }
            return players;
        });
    }

    public void setBossBarEnabled(String player, boolean enabled) {
        String key = normalizePlayerKey(player);
        if (key.isBlank()) return;

        if (enabled) {
            executeSql("database operation", () -> {
                try (Connection conn = openConnection();
                     PreparedStatement ps = conn.prepareStatement("DELETE FROM bossbar_preferences WHERE player = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
            });
            return;
        }

        executeSql("database operation", () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(bossBarUpsertSql())) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
    }

    private String bossBarUpsertSql() {
        if (backend == Backend.SQLITE) {
            return "INSERT OR REPLACE INTO bossbar_preferences (player, enabled) VALUES (?, 0)";
        }
        if (backend == Backend.POSTGRESQL) {
            return "INSERT INTO bossbar_preferences (player, enabled) VALUES (?, 0) "
                    + "ON CONFLICT (player) DO UPDATE SET enabled = 0";
        }
        return "INSERT INTO bossbar_preferences (player, enabled) VALUES (?, 0) "
                + "ON DUPLICATE KEY UPDATE enabled = 0";
    }

    private String normalizePlayerKey(String player) {
        return player == null ? "" : player.trim().toLowerCase(Locale.ROOT);
    }

    public boolean hasClaimed(String p, String id, String period) {
        return executeSql("database lookup", false, () -> {
            try (Connection c = openConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT 1 FROM claimed WHERE player=? AND reward_id=? AND period=?")) {
                ps.setString(1, p);
                ps.setString(2, id);
                ps.setString(3, period);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public void setClaimed(String p, String id, String period) {
        executeSql("database operation", () -> {
            try (Connection c = openConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO claimed VALUES(?,?,?)")) {
                ps.setString(1, p);
                ps.setString(2, id);
                ps.setString(3, period);
                ps.executeUpdate();
            }
        });
    }

    public long getTotalDonated(String player) {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE " + playerPredicate("player"))) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public long getServerTotalDonated() {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM transactions")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public List<Map.Entry<String, Long>> getTopDonators(int limit) {
        return getTopList("all", limit, 0);
    }

    public long getTransactionCount() {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM transactions")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public long getPersonalRevenue(String playerName, long startTime) {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE "
                                 + playerPredicate("player") + " AND time >= ?")) {
                ps.setString(1, playerName);
                ps.setLong(2, Math.max(0L, startTime));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public void resetTopNap() {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM transactions");
            }
        });
    }

    public void resetMilestoneClaims() {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM claimed_milestones");
            }
        });
    }

    public void resetServerMilestoneClaims() {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM claimed_server_milestones");
            }
        });
    }

    public void resetReachedServerMilestones() {
        executeSql("database operation", () -> {
            try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM reached_server_milestones");
            }
        });
    }

    public List<Map.Entry<Long, Long>> getPlayerHistory(String playerName) {
        return executeSql("database operation", new ArrayList<>(), () -> {
            List<Map.Entry<Long, Long>> history = new ArrayList<>();
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT time, amount FROM transactions WHERE "
                                 + playerPredicate("player") + " ORDER BY time DESC LIMIT 21")) {
                ps.setString(1, playerName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        history.add(new AbstractMap.SimpleEntry<>(rs.getLong("time"), rs.getLong("amount")));
                    }
                }
            }
            return history;
        });
    }

    public List<TransactionRecord> getPlayerHistoryDetailed(String playerName) {
        return getPlayerHistoryDetailed(playerName, 21);
    }

    public List<TransactionRecord> getPlayerHistoryDetailed(String playerName, int limit) {
        return executeSql("database operation", new ArrayList<>(), () -> {
            List<TransactionRecord> history = new ArrayList<>();
            int safeLimit = Math.max(1, Math.min(limit, 100));

            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, player, time, amount, type, provider, detail " +
                                 "FROM transactions WHERE " + playerPredicate("player") + " " +
                                 "ORDER BY time DESC, id DESC LIMIT ?")) {
                ps.setString(1, playerName);
                ps.setInt(2, safeLimit);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        history.add(new TransactionRecord(
                                rs.getLong("id"),
                                rs.getString("player"),
                                rs.getLong("amount"),
                                rs.getLong("time"),
                                PaymentChannel.fromStored(rs.getString("type")),
                                rs.getString("provider"),
                                rs.getString("detail")
                        ));
                    }
                }
            }
            return history;
        });
    }

    public long getPlayerTransactionCount(String playerName) {
        return executeSql("database operation", 0L, () -> {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*) FROM transactions WHERE " + playerPredicate("player"))) {
                ps.setString(1, playerName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public long getTotalDonatedIgnoreCase(String playerName) {
        return getTotalDonated(playerName);
    }

    public List<Map.Entry<String, Long>> getTopList(String mode) {
        return getTopList(mode, 21, 0);
    }

    public List<Map.Entry<String, Long>> getTopList(String mode, int limit, int offset) {
        return getTopListSince(getPeriodStartTime(mode), limit, offset);
    }

    public List<Map.Entry<String, Long>> getTopListSince(long startTime, int limit, int offset) {
        return executeSql("database operation", new ArrayList<>(), () -> {
            List<Map.Entry<String, Long>> topList = new ArrayList<>();

            int safeLimit = Math.max(1, Math.min(limit, 100));
            int safeOffset = Math.max(0, offset);
            long safeStartTime = Math.max(0L, startTime);

            boolean filteredByTime = safeStartTime > 0L;
            String playerEquality = switch (backend) {
                case SQLITE -> "t2.player COLLATE NOCASE = t.player COLLATE NOCASE";
                case POSTGRESQL -> "LOWER(t2.player) = LOWER(t.player)";
                case MYSQL, MARIADB -> "t2.player = t.player";
            };
            String playerGrouping = switch (backend) {
                case SQLITE -> "t.player COLLATE NOCASE";
                case POSTGRESQL -> "LOWER(t.player)";
                case MYSQL, MARIADB -> "t.player";
            };
            String displayOrdering = switch (backend) {
                case SQLITE -> "display_player COLLATE NOCASE ASC";
                case POSTGRESQL -> "LOWER(display_player) ASC";
                case MYSQL, MARIADB -> "display_player ASC";
            };
            String sql;
            if (backend == Backend.POSTGRESQL) {
                sql = "WITH grouped AS (" +
                        "SELECT LOWER(t.player) AS player_key, COALESCE(SUM(t.amount), 0) AS total " +
                        "FROM transactions t " +
                        "WHERE t.player IS NOT NULL AND TRIM(t.player) <> '' " +
                        (filteredByTime ? "AND t.time >= ? " : "") +
                        "GROUP BY LOWER(t.player)" +
                        "), latest AS (" +
                        "SELECT DISTINCT ON (LOWER(t.player)) LOWER(t.player) AS player_key, " +
                        "t.player AS display_player " +
                        "FROM transactions t " +
                        "WHERE t.player IS NOT NULL AND TRIM(t.player) <> '' " +
                        "ORDER BY LOWER(t.player), t.time DESC, t.id DESC" +
                        ") " +
                        "SELECT latest.display_player, grouped.total " +
                        "FROM grouped JOIN latest USING (player_key) " +
                        "ORDER BY grouped.total DESC, LOWER(latest.display_player) ASC " +
                        "LIMIT ? OFFSET ?";
            } else {
                sql = "SELECT " +
                        "COALESCE((" +
                        "SELECT t2.player FROM transactions t2 " +
                        "WHERE " + playerEquality + " " +
                        "ORDER BY t2.time DESC, t2.id DESC LIMIT 1" +
                        "), t.player) AS display_player, " +
                        "COALESCE(SUM(t.amount), 0) AS total " +
                        "FROM transactions t " +
                        "WHERE t.player IS NOT NULL AND TRIM(t.player) <> '' " +
                        (filteredByTime ? "AND t.time >= ? " : "") +
                        "GROUP BY " + playerGrouping + " " +
                        "ORDER BY total DESC, " + displayOrdering + " " +
                        "LIMIT ? OFFSET ?";
            }

            try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                if (filteredByTime) {
                    ps.setLong(index++, safeStartTime);
                }
                ps.setInt(index++, safeLimit);
                ps.setInt(index, safeOffset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        topList.add(new AbstractMap.SimpleEntry<>(
                                rs.getString("display_player"),
                                rs.getLong("total")
                        ));
                    }
                }
            }

            return topList;
        });
    }

    private String playerPredicate(String column) {
        return switch (backend) {
            case SQLITE -> column + " = ? COLLATE NOCASE";
            case POSTGRESQL -> "LOWER(" + column + ") = LOWER(?)";
            case MYSQL, MARIADB -> column + " = ?";
        };
    }

    public String getBackendName() {
        return backend.displayName;
    }

    public boolean isRemoteBackend() {
        return backend.isRemote();
    }

    /**
     * Opens a connection from the pool. Caller must close it when done.
     * Used by migration utilities and external components that need direct access.
     */
    public Connection getConnection() throws SQLException {
        return openConnection();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        schemaReady = false;
        try {
            dataSource.close();
        } finally {
            shutdownOwnedJdbcResources();
        }
    }

    public void shutdown() {
        close();
    }

    private void shutdownOwnedJdbcResources() {
        if (backend == Backend.MYSQL) {
            try {
                AbandonedConnectionCleanupThread.checkedShutdown();
            } catch (RuntimeException | LinkageError exception) {
                plugin.logWarning("Could not stop the MySQL JDBC cleanup thread cleanly: "
                        + exception.getMessage());
            }
        }

        ClassLoader pluginClassLoader = DatabaseManager.class.getClassLoader();
        Enumeration<Driver> registeredDrivers = DriverManager.getDrivers();
        while (registeredDrivers.hasMoreElements()) {
            Driver driver = registeredDrivers.nextElement();
            if (driver.getClass().getClassLoader() != pluginClassLoader) continue;

            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException | SecurityException exception) {
                plugin.logWarning("Could not deregister owned JDBC driver "
                        + driver.getClass().getName() + ": " + exception.getMessage());
            }
        }
    }

    private void logSqlException(String action, SQLException exception) {
        if (plugin != null) {
            plugin.logWarning("Database error during " + action + ": " + exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}

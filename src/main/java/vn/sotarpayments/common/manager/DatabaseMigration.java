package vn.sotarpayments.common.manager;

import vn.sotarpayments.SotarPayments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility for migrating data between database backends (e.g., SQLite to MySQL).
 * This is a one-way data migration; schema is always recreated on the target backend.
 */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    /**
     * Migrates all data from a source database file/path to the currently configured backend.
     *
     * @param plugin    the plugin instance
     * @param sourceType the source backend type ("sqlite", "mysql", etc.)
     * @param sourceConfig path to source config (for SQLite: file path; for remote: host:port/database)
     * @param listener  progress callback
     * @return migration result
     */
    public static MigrationResult migrate(SotarPayments plugin,
                                           String sourceType,
                                           String sourceConfig,
                                           MigrationListener listener) {
        String normalizedType = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.ROOT);
        if (!"sqlite".equals(normalizedType)) {
            return new MigrationResult(false, "Source backend must be 'sqlite' for migration.");
        }

        try {
            return migrateFromSqlite(plugin, sourceConfig, listener);
        } catch (Exception e) {
            return new MigrationResult(false, "Migration failed: " + e.getMessage());
        }
    }

    private static MigrationResult migrateFromSqlite(SotarPayments plugin,
                                                      String sqliteFilePath,
                                                      MigrationListener listener) throws SQLException {
        String targetBackend = plugin.getDatabaseManager().getBackendName();
        if ("SQLite".equals(targetBackend)) {
            return new MigrationResult(false, "Cannot migrate from SQLite to SQLite. Target must be MySQL, MariaDB, or PostgreSQL.");
        }

        java.nio.file.Path sourcePath = resolveSourceSqlitePath(plugin, sqliteFilePath);
        if (!java.nio.file.Files.exists(sourcePath)) {
            return new MigrationResult(false, "Source SQLite file not found: " + sourcePath);
        }

        listener.onProgress("Connecting to source SQLite database...");
        String sourceJdbc = "jdbc:sqlite:" + sourcePath;

        DatabaseManager targetDb = plugin.getDatabaseManager();
        List<String> tables = List.of("transactions", "claimed", "claimed_milestones",
                "claimed_server_milestones", "reached_server_milestones", "bossbar_preferences");

        int totalRows = 0;
        int migratedRows = 0;

        try (java.sql.Connection sourceConn = java.sql.DriverManager.getConnection(sourceJdbc)) {
            for (String table : tables) {
                int rowCount = getRowCount(sourceConn, table);
                totalRows += rowCount;
            }
        }

        listener.onProgress("Source has " + totalRows + " total rows across " + tables.size() + " tables.");

        for (String table : tables) {
            int rowCount = getRowCountSqlite(sourcePath, table);
            if (rowCount == 0) {
                listener.onProgress("Skipping empty table: " + table);
                continue;
            }

            listener.onProgress("Migrating table '" + table + "' (" + rowCount + " rows)...");
            migratedRows += migrateTable(plugin, sourcePath, table, listener);
            listener.onProgress("Completed table '" + table + "': " + migratedRows + "/" + totalRows + " rows.");
        }

        return new MigrationResult(true,
                "Migration completed successfully. " + migratedRows + " rows migrated to " + targetBackend + ".");
    }

    private static int migrateTable(SotarPayments plugin,
                                     java.nio.file.Path sourcePath,
                                     String table,
                                     MigrationListener listener) throws SQLException {
        String sourceJdbc = "jdbc:sqlite:" + sourcePath;
        int migrated = 0;

        try (java.sql.Connection sourceConn = java.sql.DriverManager.getConnection(sourceJdbc);
             Statement sourceStmt = sourceConn.createStatement();
             ResultSet rs = sourceStmt.executeQuery("SELECT * FROM " + table)) {

            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            StringBuilder insertBuilder = new StringBuilder("INSERT INTO ")
                    .append(table)
                    .append(" (");
            StringBuilder placeholders = new StringBuilder();

            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                String colName = meta.getColumnName(i);
                columns.add(colName);
                if (i > 1) {
                    insertBuilder.append(", ");
                    placeholders.append(", ");
                }
                insertBuilder.append(colName);
                placeholders.append("?");
            }
            insertBuilder.append(") VALUES (").append(placeholders).append(")");

            String insertSql = insertBuilder.toString();

            try (java.sql.Connection targetConn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = targetConn.prepareStatement(insertSql)) {

                int batchSize = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        ps.setObject(i, rs.getObject(i));
                    }
                    ps.addBatch();
                    batchSize++;

                    if (batchSize >= 500) {
                        ps.executeBatch();
                        ps.clearBatch();
                        migrated += batchSize;
                        batchSize = 0;
                    }
                }

                if (batchSize > 0) {
                    ps.executeBatch();
                    migrated += batchSize;
                }
            }
        }

        return migrated;
    }

    private static int getRowCountSqlite(java.nio.file.Path sourcePath, String table) {
        String jdbc = "jdbc:sqlite:" + sourcePath;
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbc);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static int getRowCount(java.sql.Connection conn, String table) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static java.nio.file.Path resolveSourceSqlitePath(SotarPayments plugin, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return plugin.getDataFolder().toPath().resolve("database.sqlite").normalize();
        }
        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        if (path.isAbsolute()) return path.normalize();
        return plugin.getDataFolder().toPath().resolve(filePath).normalize();
    }

    public record MigrationResult(boolean success, String message) {}

    @FunctionalInterface
    public interface MigrationListener {
        void onProgress(String message);
    }
}

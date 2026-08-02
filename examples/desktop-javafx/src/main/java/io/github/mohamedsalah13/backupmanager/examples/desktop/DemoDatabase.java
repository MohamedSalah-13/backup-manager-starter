package io.github.mohamedsalah13.backupmanager.examples.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A small SQLite database standing in for whatever the real application stores, so the
 * example runs with no setup at all.
 */
final class DemoDatabase {

    private static final Logger log = LoggerFactory.getLogger(DemoDatabase.class);

    private final Path file;

    DemoDatabase(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path getFile() {
        return file;
    }

    String jdbcUrl() {
        return "jdbc:sqlite:" + file;
    }

    void createIfMissing() throws Exception {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl());
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS customers (
                        id      INTEGER PRIMARY KEY,
                        name    TEXT NOT NULL,
                        balance REAL NOT NULL
                    )
                    """);

            if (countRows() > 0) {
                return;
            }
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO customers (id, name, balance) VALUES (?, ?, ?)")) {
                for (int i = 1; i <= 500; i++) {
                    insert.setInt(1, i);
                    // Apostrophes and non-ASCII text, the values a careless dump mangles.
                    insert.setString(2, i % 4 == 0 ? "O'Neill Ltd" : "عميل رقم " + i);
                    insert.setDouble(3, i * 12.5);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            log.info("Created the demo database at {} with 500 customers", file);
        }
    }

    long countRows() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM customers")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Adds a row, so the effect of restoring an older backup is visible. */
    void addCustomer(String name) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl());
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO customers (id, name, balance)"
                             + " VALUES ((SELECT COALESCE(MAX(id), 0) + 1 FROM customers), ?, ?)")) {
            insert.setString(1, name);
            insert.setDouble(2, 999.0);
            insert.executeUpdate();
        }
    }
}

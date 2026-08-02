package io.github.mohamedsalah13.backupmanager.examples.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a small invoices table and some documents on first start, so the example has
 * something real to back up and restore without any manual setup.
 */
@Configuration
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    @Bean
    ApplicationRunner seedDatabase(JdbcTemplate jdbc) {
        return args -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS invoices (
                        id          INT PRIMARY KEY,
                        customer    VARCHAR(120) NOT NULL,
                        total       DECIMAL(12,2) NOT NULL,
                        issued_on   DATE NOT NULL
                    )
                    """);

            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class);
            if (existing != null && existing > 0) {
                log.info("Database already holds {} invoice(s); leaving it alone", existing);
                return;
            }

            for (int i = 1; i <= 250; i++) {
                jdbc.update("INSERT INTO invoices (id, customer, total, issued_on) VALUES (?, ?, ?, ?)",
                        i,
                        // An apostrophe and a non-ASCII name, because those are what a
                        // naive dump-and-restore gets wrong.
                        i % 3 == 0 ? "O'Brien & Sons" : "شركة النور " + i,
                        i * 137.45,
                        java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(i % 90)));
            }
            log.info("Seeded 250 invoices");
        };
    }

    @Bean
    ApplicationRunner seedDocuments() {
        return args -> {
            Path documents = Path.of("./data/documents");
            if (Files.exists(documents)) {
                return;
            }
            Files.createDirectories(documents.resolve("contracts"));
            Files.writeString(documents.resolve("readme.txt"),
                    "Files in this folder are covered by the 'user-documents' backup job.\n");
            Files.writeString(documents.resolve("contracts/contract-001.txt"),
                    "Sample contract body.\n".repeat(50));
            Files.writeString(documents.resolve("scratch.tmp"),
                    "Excluded by the **/*.tmp rule and should never appear in a restore.\n");
            log.info("Seeded sample documents in {}", documents.toAbsolutePath());
        };
    }
}

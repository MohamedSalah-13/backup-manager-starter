package com.backupmanager.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/** Artifact naming, path sanitising and scratch-directory cleanup. */
public final class FileNames {

    /** Sorts lexicographically in the same order as chronologically, and is filename-safe. */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** Reserved on Windows regardless of extension. */
    private static final java.util.Set<String> WINDOWS_RESERVED = java.util.Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() {
    }

    /**
     * Builds an artifact name such as {@code sales-20260802-143012.sql.gz.enc}.
     *
     * @param extensions raw payload extension first, then each pipeline stage's suffix
     */
    public static String artifactName(String jobName, Instant when, ZoneId zone, String... extensions) {
        StringBuilder sb = new StringBuilder(sanitize(jobName));
        sb.append('-').append(TIMESTAMP.format(when.atZone(zone != null ? zone : ZoneId.systemDefault())));
        for (String ext : extensions) {
            if (ext == null || ext.isBlank()) {
                continue;
            }
            sb.append(ext.startsWith(".") ? ext : "." + ext);
        }
        return sb.toString();
    }

    /**
     * Replaces every character that is problematic in a path on any supported platform.
     * Also guards against traversal, since job names can come from user input.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "backup";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            boolean illegal = c < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|';
            sb.append(illegal ? '_' : c);
        }
        // Windows silently strips trailing dots and spaces, which would break exact-name lookups.
        String cleaned = sb.toString().strip();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return "backup";
        }
        String upperStem = cleaned.toUpperCase(Locale.ROOT);
        int dot = upperStem.indexOf('.');
        if (WINDOWS_RESERVED.contains(dot > 0 ? upperStem.substring(0, dot) : upperStem)) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /** Joins into a forward-slash remote path, which every storage backend accepts. */
    public static String remotePath(String prefix, String fileName) {
        String cleanName = sanitize(fileName);
        if (prefix == null || prefix.isBlank()) {
            return cleanName;
        }
        String cleanPrefix = prefix.replace('\\', '/');
        while (cleanPrefix.endsWith("/")) {
            cleanPrefix = cleanPrefix.substring(0, cleanPrefix.length() - 1);
        }
        while (cleanPrefix.startsWith("/")) {
            cleanPrefix = cleanPrefix.substring(1);
        }
        return cleanPrefix.isEmpty() ? cleanName : cleanPrefix + "/" + cleanName;
    }

    /** Deletes a directory tree, never throwing — used in {@code finally} blocks. */
    public static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException | SecurityException ignored) {
            // best effort
        }
    }

    /** Bytes rendered for a UI label: {@code 1.4 GB}. */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}

package com.backupmanager.core.util;

import com.backupmanager.core.model.BackupException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Digest helpers used to prove a stored artifact still matches what was written. */
public final class Checksums {

    public static final String DEFAULT_ALGORITHM = "SHA-256";

    private Checksums() {
    }

    public static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new BackupException.Configuration("Unsupported checksum algorithm: " + algorithm, e);
        }
    }

    /** Wraps {@code out} so everything written is also digested. */
    public static DigestOutputStream digesting(OutputStream out, String algorithm) {
        return new DigestOutputStream(out, digest(algorithm));
    }

    public static DigestInputStream digesting(InputStream in, String algorithm) {
        return new DigestInputStream(in, digest(algorithm));
    }

    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Digest of a whole file, streamed so size does not matter. */
    public static String ofFile(Path file, String algorithm) throws IOException {
        MessageDigest md = digest(algorithm);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return hex(md);
    }

    public static String ofFile(Path file) throws IOException {
        return ofFile(file, DEFAULT_ALGORITHM);
    }

    /** Constant-time comparison, case-insensitive on the hex digits. */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= Character.toLowerCase(expected.charAt(i)) ^ Character.toLowerCase(actual.charAt(i));
        }
        return diff == 0;
    }
}

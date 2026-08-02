package io.github.mohamedsalah13.backupmanager.core.crypto;

import io.github.mohamedsalah13.backupmanager.core.api.Encryptor;
import io.github.mohamedsalah13.backupmanager.core.model.BackupException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Authenticated encryption with AES-256-GCM over independently sealed chunks.
 *
 * <p>Plain {@code CipherOutputStream} is a poor fit for backups: GCM only verifies its tag
 * at the very end, so a multi-gigabyte artifact has to be fully buffered or fully trusted
 * before anything can be used, and some JDK versions swallow the tag failure entirely.
 * Sealing fixed-size chunks instead means corruption is caught within one chunk, memory
 * stays flat, and a truncated file is detected because the terminator is missing.
 *
 * <p>Wire format, all integers big-endian:
 * <pre>
 *   "BMENC" | version(1) | kdfId(1) | iterations(4) | saltLen(1) | salt | noncePrefix(8) | chunkSize(4)
 *   repeated: cipherLen(4) | ciphertext+tag
 *   terminator: 0x00000000
 * </pre>
 *
 * <p>The nonce for chunk <i>i</i> is the 8-byte per-file random prefix followed by <i>i</i>
 * as a 4-byte counter, so no nonce is ever reused under one key. The header and the chunk
 * index are fed in as additional authenticated data, which makes both header tampering and
 * chunk reordering detectable.
 */
public final class AesGcmEncryptor implements Encryptor {

    public static final String ID = "aes-gcm";

    private static final byte[] MAGIC = {'B', 'M', 'E', 'N', 'C'};
    private static final byte VERSION = 1;
    private static final byte KDF_PBKDF2_SHA256 = 1;
    /** Key supplied directly by the caller; no salt or iteration count applies. */
    private static final byte KDF_RAW = 2;

    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int NONCE_BYTES = 12;
    /** 1 MiB keeps per-chunk tag overhead negligible while bounding memory. */
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    /** Refuse absurd headers rather than allocating whatever a corrupt file asks for. */
    private static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;
    /** OWASP guidance for PBKDF2-HMAC-SHA256. */
    private static final int DEFAULT_ITERATIONS = 210_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final char[] password;
    private final byte[] rawKey;
    private final int iterations;
    private final int chunkSize;

    /** Derivation is slow by design, so the result is reused across chunks of one stream. */
    private volatile CachedKey cachedKey;

    private AesGcmEncryptor(char[] password, byte[] rawKey, int iterations, int chunkSize) {
        this.password = password;
        this.rawKey = rawKey;
        this.iterations = iterations;
        this.chunkSize = chunkSize;
    }

    /** Password-based, with default PBKDF2 iterations. */
    public static AesGcmEncryptor withPassword(char[] password) {
        return withPassword(password, DEFAULT_ITERATIONS, DEFAULT_CHUNK_SIZE);
    }

    public static AesGcmEncryptor withPassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new BackupException.Configuration("Encryption password must not be empty");
        }
        return withPassword(password.toCharArray());
    }

    public static AesGcmEncryptor withPassword(char[] password, int iterations, int chunkSize) {
        if (password == null || password.length == 0) {
            throw new BackupException.Configuration("Encryption password must not be empty");
        }
        if (iterations < 10_000) {
            throw new BackupException.Configuration(
                    "PBKDF2 iterations must be at least 10000 but was " + iterations);
        }
        validateChunkSize(chunkSize);
        return new AesGcmEncryptor(password.clone(), null, iterations, chunkSize);
    }

    /**
     * Uses a 32-byte key directly, skipping derivation. Prefer this when the key already
     * lives in a keystore or an HSM.
     */
    public static AesGcmEncryptor withKey(byte[] key256) {
        if (key256 == null || key256.length != KEY_BITS / 8) {
            throw new BackupException.Configuration(
                    "AES-256 key must be exactly 32 bytes but was "
                            + (key256 == null ? "null" : key256.length + " bytes"));
        }
        return new AesGcmEncryptor(null, key256.clone(), 0, DEFAULT_CHUNK_SIZE);
    }

    /** Fresh random 32-byte key, for callers that will store it themselves. */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_BITS / 8];
        RANDOM.nextBytes(key);
        return key;
    }

    private static void validateChunkSize(int chunkSize) {
        if (chunkSize < 4096 || chunkSize > MAX_CHUNK_SIZE) {
            throw new BackupException.Configuration(
                    "Chunk size must be between 4096 and " + MAX_CHUNK_SIZE + " but was " + chunkSize);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String extension() {
        return ".enc";
    }

    @Override
    public OutputStream encrypt(OutputStream out) throws IOException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        if (rawKey == null) {
            RANDOM.nextBytes(salt);
        }
        RANDOM.nextBytes(noncePrefix);

        byte[] header = buildHeader(salt, noncePrefix);
        out.write(header);

        SecretKey key = resolveKey(salt);
        return new ChunkedGcmOutputStream(out, key, noncePrefix, header, chunkSize);
    }

    @Override
    public InputStream decrypt(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);

        byte[] magic = new byte[MAGIC.length];
        data.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new BackupException.Crypto(
                    "Artifact is not encrypted with this library (bad magic header)", null);
        }
        byte version = data.readByte();
        if (version != VERSION) {
            throw new BackupException.Crypto(
                    "Unsupported encryption format version " + version + "; this build understands " + VERSION,
                    null);
        }
        byte kdfId = data.readByte();
        int storedIterations = data.readInt();
        int saltLen = data.readUnsignedByte();
        byte[] salt = new byte[saltLen];
        data.readFully(salt);
        byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
        data.readFully(noncePrefix);
        int storedChunkSize = data.readInt();
        if (storedChunkSize < 1 || storedChunkSize > MAX_CHUNK_SIZE) {
            throw new BackupException.Crypto(
                    "Artifact declares an implausible chunk size (" + storedChunkSize + "); it is corrupt", null);
        }

        if (kdfId == KDF_RAW && rawKey == null) {
            throw new BackupException.Crypto(
                    "Artifact was encrypted with a raw key but a password was supplied", null);
        }
        if (kdfId == KDF_PBKDF2_SHA256 && rawKey != null) {
            throw new BackupException.Crypto(
                    "Artifact was encrypted with a password but a raw key was supplied", null);
        }

        // Re-derive using the iteration count recorded in the artifact, not the current
        // configuration, so raising the default later does not orphan old backups.
        SecretKey key = resolveKeyForDecrypt(salt, storedIterations);
        byte[] header = rebuildHeader(kdfId, storedIterations, salt, noncePrefix, storedChunkSize);

        return new ChunkedGcmInputStream(data, key, noncePrefix, header, storedChunkSize);
    }

    private byte[] buildHeader(byte[] salt, byte[] noncePrefix) throws IOException {
        byte kdfId = rawKey != null ? KDF_RAW : KDF_PBKDF2_SHA256;
        int effectiveIterations = rawKey != null ? 0 : iterations;
        return rebuildHeader(kdfId, effectiveIterations, salt, noncePrefix, chunkSize);
    }

    private byte[] rebuildHeader(byte kdfId, int iterationCount, byte[] salt,
                                 byte[] noncePrefix, int size) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(kdfId);
        out.writeInt(iterationCount);
        out.writeByte(salt.length);
        out.write(salt);
        out.write(noncePrefix);
        out.writeInt(size);
        out.flush();
        return buffer.toByteArray();
    }

    private SecretKey resolveKey(byte[] salt) {
        if (rawKey != null) {
            return new SecretKeySpec(rawKey, "AES");
        }
        return deriveKey(salt, iterations);
    }

    private SecretKey resolveKeyForDecrypt(byte[] salt, int storedIterations) {
        if (rawKey != null) {
            return new SecretKeySpec(rawKey, "AES");
        }
        return deriveKey(salt, storedIterations);
    }

    private SecretKey deriveKey(byte[] salt, int iterationCount) {
        CachedKey cached = cachedKey;
        if (cached != null && cached.matches(salt, iterationCount)) {
            return cached.key();
        }
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, KEY_BITS);
            try {
                SecretKey derived = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
                cachedKey = new CachedKey(salt.clone(), iterationCount, derived);
                return derived;
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException e) {
            throw new BackupException.Crypto("Could not derive encryption key", e);
        }
    }

    private record CachedKey(byte[] salt, int iterations, SecretKey key) {
        boolean matches(byte[] otherSalt, int otherIterations) {
            return iterations == otherIterations && Arrays.equals(salt, otherSalt);
        }
    }

    private static byte[] nonceFor(byte[] prefix, int chunkIndex) {
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_BYTES);
        nonce[8] = (byte) (chunkIndex >>> 24);
        nonce[9] = (byte) (chunkIndex >>> 16);
        nonce[10] = (byte) (chunkIndex >>> 8);
        nonce[11] = (byte) chunkIndex;
        return nonce;
    }

    private static byte[] aadFor(byte[] header, int chunkIndex) {
        byte[] aad = Arrays.copyOf(header, header.length + 4);
        aad[header.length] = (byte) (chunkIndex >>> 24);
        aad[header.length + 1] = (byte) (chunkIndex >>> 16);
        aad[header.length + 2] = (byte) (chunkIndex >>> 8);
        aad[header.length + 3] = (byte) chunkIndex;
        return aad;
    }

    /** Buffers plaintext up to the chunk size, then seals and emits each chunk. */
    private static final class ChunkedGcmOutputStream extends OutputStream {

        private final OutputStream out;
        private final SecretKey key;
        private final byte[] noncePrefix;
        private final byte[] header;
        private final byte[] buffer;
        private final Cipher cipher;

        private int position;
        private int chunkIndex;
        private boolean closed;

        ChunkedGcmOutputStream(OutputStream out, SecretKey key, byte[] noncePrefix,
                               byte[] header, int chunkSize) {
            this.out = out;
            this.key = key;
            this.noncePrefix = noncePrefix;
            this.header = header;
            this.buffer = new byte[chunkSize];
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new BackupException.Crypto("AES/GCM is unavailable in this JVM", e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            buffer[position++] = (byte) b;
            if (position == buffer.length) {
                sealChunk();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int room = buffer.length - position;
                int take = Math.min(room, remaining);
                System.arraycopy(b, offset, buffer, position, take);
                position += take;
                offset += take;
                remaining -= take;
                if (position == buffer.length) {
                    sealChunk();
                }
            }
        }

        private void sealChunk() throws IOException {
            if (position == 0) {
                return;
            }
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key,
                        new GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, chunkIndex)));
                cipher.updateAAD(aadFor(header, chunkIndex));
                byte[] sealed = cipher.doFinal(buffer, 0, position);

                writeInt(out, sealed.length);
                out.write(sealed);
            } catch (GeneralSecurityException e) {
                throw new BackupException.Crypto("Failed to encrypt chunk " + chunkIndex, e);
            }
            chunkIndex++;
            position = 0;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
        }

        @Override
        public void flush() throws IOException {
            // A partial chunk cannot be emitted without ending it, so only the sink is flushed.
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                sealChunk();
                // Zero-length frame marks a clean end; without it a truncated file
                // would look like a complete one.
                writeInt(out, 0);
                out.flush();
            } finally {
                closed = true;
                Arrays.fill(buffer, (byte) 0);
                out.close();
            }
        }

        private static void writeInt(OutputStream out, int value) throws IOException {
            out.write((value >>> 24) & 0xFF);
            out.write((value >>> 16) & 0xFF);
            out.write((value >>> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }

    /** Reads one sealed chunk at a time and serves its verified plaintext. */
    private static final class ChunkedGcmInputStream extends InputStream {

        private final DataInputStream in;
        private final SecretKey key;
        private final byte[] noncePrefix;
        private final byte[] header;
        private final int maxCipherLength;
        private final Cipher cipher;

        private byte[] plain = new byte[0];
        private int position;
        private int chunkIndex;
        private boolean finished;

        ChunkedGcmInputStream(DataInputStream in, SecretKey key, byte[] noncePrefix,
                              byte[] header, int chunkSize) {
            this.in = in;
            this.key = key;
            this.noncePrefix = noncePrefix;
            this.header = header;
            this.maxCipherLength = chunkSize + TAG_BYTES;
            try {
                this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new BackupException.Crypto("AES/GCM is unavailable in this JVM", e);
            }
        }

        @Override
        public int read() throws IOException {
            if (!ensureAvailable()) {
                return -1;
            }
            return plain[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (!ensureAvailable()) {
                return -1;
            }
            int take = Math.min(len, plain.length - position);
            System.arraycopy(plain, position, b, off, take);
            position += take;
            return take;
        }

        @Override
        public int available() {
            return plain.length - position;
        }

        private boolean ensureAvailable() throws IOException {
            while (position >= plain.length) {
                if (finished) {
                    return false;
                }
                readNextChunk();
            }
            return true;
        }

        private void readNextChunk() throws IOException {
            int cipherLength;
            try {
                cipherLength = in.readInt();
            } catch (EOFException e) {
                throw new BackupException.Crypto(
                        "Encrypted artifact is truncated: the end-of-stream marker is missing", e);
            }

            if (cipherLength == 0) {
                finished = true;
                plain = new byte[0];
                position = 0;
                return;
            }
            if (cipherLength < TAG_BYTES || cipherLength > maxCipherLength) {
                throw new BackupException.Crypto(
                        "Encrypted artifact is corrupt: chunk " + chunkIndex
                                + " declares an invalid length of " + cipherLength, null);
            }

            byte[] sealed = new byte[cipherLength];
            try {
                in.readFully(sealed);
            } catch (EOFException e) {
                throw new BackupException.Crypto(
                        "Encrypted artifact is truncated inside chunk " + chunkIndex, e);
            }

            try {
                cipher.init(Cipher.DECRYPT_MODE, key,
                        new GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, chunkIndex)));
                cipher.updateAAD(aadFor(header, chunkIndex));
                plain = cipher.doFinal(sealed);
                position = 0;
                chunkIndex++;
            } catch (AEADBadTagException e) {
                throw new BackupException.Crypto(
                        "Decryption failed at chunk " + chunkIndex
                                + ". The password is wrong or the artifact has been altered.", e);
            } catch (GeneralSecurityException e) {
                throw new BackupException.Crypto("Failed to decrypt chunk " + chunkIndex, e);
            }
        }

        @Override
        public void close() throws IOException {
            Arrays.fill(plain, (byte) 0);
            in.close();
        }
    }
}

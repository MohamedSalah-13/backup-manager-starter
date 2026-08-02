package com.backupmanager.core.crypto;

import com.backupmanager.core.model.BackupException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptorTest {

    private static byte[] encrypt(AesGcmEncryptor encryptor, byte[] plaintext) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = encryptor.encrypt(sink)) {
            out.write(plaintext);
        }
        return sink.toByteArray();
    }

    private static byte[] decrypt(AesGcmEncryptor encryptor, byte[] ciphertext) throws Exception {
        try (InputStream in = encryptor.decrypt(new ByteArrayInputStream(ciphertext))) {
            return in.readAllBytes();
        }
    }

    @Test
    void roundTripsShortPayload() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("correct horse battery staple");
        byte[] plaintext = "SELECT * FROM invoices;".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = encrypt(encryptor, plaintext);

        assertThat(sealed).isNotEqualTo(plaintext);
        assertThat(decrypt(encryptor, sealed)).isEqualTo(plaintext);
    }

    @Test
    void roundTripsPayloadSpanningManyChunks() throws Exception {
        // 5 MiB against a 1 MiB chunk size exercises the chunk boundary logic.
        byte[] plaintext = new byte[5 * 1024 * 1024 + 12345];
        new Random(42).nextBytes(plaintext);
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("a-long-enough-password");

        byte[] sealed = encrypt(encryptor, plaintext);

        assertThat(decrypt(encryptor, sealed)).isEqualTo(plaintext);
    }

    @Test
    void roundTripsEmptyPayload() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] sealed = encrypt(encryptor, new byte[0]);

        assertThat(decrypt(encryptor, sealed)).isEmpty();
    }

    @Test
    void producesDifferentCiphertextForIdenticalInput() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] plaintext = "same input".getBytes(StandardCharsets.UTF_8);

        // A fresh salt and nonce prefix per artifact must make the outputs differ.
        assertThat(encrypt(encryptor, plaintext)).isNotEqualTo(encrypt(encryptor, plaintext));
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        byte[] sealed = encrypt(AesGcmEncryptor.withPassword("right"), "secret".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decrypt(AesGcmEncryptor.withPassword("wrong"), sealed))
                .isInstanceOf(BackupException.Crypto.class)
                .hasMessageContaining("password is wrong");
    }

    @Test
    void detectsTamperedCiphertext() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] sealed = encrypt(encryptor, "important data".getBytes(StandardCharsets.UTF_8));

        // Flip a bit well past the header, inside the sealed chunk.
        sealed[sealed.length - 5] ^= 0x01;

        assertThatThrownBy(() -> decrypt(encryptor, sealed))
                .isInstanceOf(BackupException.Crypto.class);
    }

    @Test
    void detectsTamperedHeader() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] sealed = encrypt(encryptor, "important data".getBytes(StandardCharsets.UTF_8));

        // The salt sits inside the header, which is bound in as additional authenticated data.
        sealed[15] ^= 0x01;

        assertThatThrownBy(() -> decrypt(encryptor, sealed))
                .isInstanceOf(BackupException.Crypto.class);
    }

    @Test
    void detectsTruncatedArtifact() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] plaintext = new byte[3 * 1024 * 1024];
        new Random(7).nextBytes(plaintext);
        byte[] sealed = encrypt(encryptor, plaintext);

        // Cut off the final chunk and the terminator, as a half-finished upload would.
        byte[] truncated = new byte[sealed.length - 1024];
        System.arraycopy(sealed, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> decrypt(encryptor, truncated))
                .isInstanceOf(BackupException.Crypto.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void rejectsArtifactMissingOnlyItsTerminator() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] sealed = encrypt(encryptor, "data".getBytes(StandardCharsets.UTF_8));

        byte[] withoutTerminator = new byte[sealed.length - 4];
        System.arraycopy(sealed, 0, withoutTerminator, 0, withoutTerminator.length);

        assertThatThrownBy(() -> decrypt(encryptor, withoutTerminator))
                .isInstanceOf(BackupException.Crypto.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void roundTripsWithRawKey() throws Exception {
        byte[] key = AesGcmEncryptor.generateKey();
        AesGcmEncryptor encryptor = AesGcmEncryptor.withKey(key);
        byte[] plaintext = "raw key payload".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = encrypt(encryptor, plaintext);

        assertThat(decrypt(AesGcmEncryptor.withKey(key), sealed)).isEqualTo(plaintext);
    }

    @Test
    void refusesPasswordWhenArtifactUsedRawKey() throws Exception {
        byte[] sealed = encrypt(AesGcmEncryptor.withKey(AesGcmEncryptor.generateKey()),
                "x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decrypt(AesGcmEncryptor.withPassword("pw"), sealed))
                .isInstanceOf(BackupException.Crypto.class)
                .hasMessageContaining("raw key");
    }

    @Test
    void rejectsPlaintextMasqueradingAsEncrypted() {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");

        assertThatThrownBy(() -> decrypt(encryptor, "not encrypted at all".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BackupException.Crypto.class)
                .hasMessageContaining("magic header");
    }

    @Test
    void rejectsEmptyPassword() {
        assertThatThrownBy(() -> AesGcmEncryptor.withPassword(""))
                .isInstanceOf(BackupException.Configuration.class);
    }

    @Test
    void rejectsShortKey() {
        assertThatThrownBy(() -> AesGcmEncryptor.withKey(new byte[16]))
                .isInstanceOf(BackupException.Configuration.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void readsSingleBytesAndBulkIdentically() throws Exception {
        AesGcmEncryptor encryptor = AesGcmEncryptor.withPassword("pw");
        byte[] plaintext = new byte[100_000];
        new Random(11).nextBytes(plaintext);
        byte[] sealed = encrypt(encryptor, plaintext);

        ByteArrayOutputStream oneByteAtATime = new ByteArrayOutputStream();
        try (InputStream in = encryptor.decrypt(new ByteArrayInputStream(sealed))) {
            int b;
            while ((b = in.read()) != -1) {
                oneByteAtATime.write(b);
            }
        }

        assertThat(oneByteAtATime.toByteArray()).isEqualTo(plaintext);
    }
}

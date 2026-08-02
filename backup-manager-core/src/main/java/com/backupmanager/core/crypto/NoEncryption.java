package com.backupmanager.core.crypto;

import com.backupmanager.core.api.Encryptor;

import java.io.InputStream;
import java.io.OutputStream;

/** Pass-through stage used when encryption is switched off. */
public final class NoEncryption implements Encryptor {

    public static final NoEncryption INSTANCE = new NoEncryption();

    @Override
    public String id() {
        return "none";
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public OutputStream encrypt(OutputStream out) {
        return out;
    }

    @Override
    public InputStream decrypt(InputStream in) {
        return in;
    }

    @Override
    public String extension() {
        return "";
    }
}

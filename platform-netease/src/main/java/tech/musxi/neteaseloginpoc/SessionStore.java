package tech.musxi.neteaseloginpoc;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private, non-backed-up storage. Never persists a plaintext login session. */
public final class SessionStore {
    private static final Object LOCK = new Object();
    private static final String KEY_ALIAS = "musxi_netease_login_poc";
    private static final byte[] MAGIC = { 'M', 'N', 'S', '1' };
    private static final byte[] AAD =
            "Musxi.Netease.LoginPoC.Session.v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LENGTH = 12;
    private static final int MAX_PLAINTEXT_BYTES = 32 * 1024;
    private static final int MAX_FILE_BYTES = MAX_PLAINTEXT_BYTES + 64;

    private final AtomicFile sessionFile;

    public SessionStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Application context is required.");
        }
        File directory = context.getNoBackupFilesDir();
        if (directory == null) {
            throw new IllegalStateException("Private storage is unavailable.");
        }
        sessionFile = new AtomicFile(new File(directory, "netease-session.enc"));
    }

    /** Returns null only when no saved session exists. Corruption is an explicit error. */
    public NeteaseApi.Session load() throws IOException {
        synchronized (LOCK) {
            if (!hasCommittedFile(sessionFile)) {
                return null;
            }
            byte[] encrypted = null;
            byte[] plaintext = null;
            try {
                encrypted = readBounded(sessionFile, MAX_FILE_BYTES);
                int headerLength = MAGIC.length + 1 + IV_LENGTH;
                if (encrypted.length < headerLength + 16
                        || encrypted[MAGIC.length] != IV_LENGTH) {
                    throw new IOException("Saved login has an invalid format.");
                }
                for (int i = 0; i < MAGIC.length; i++) {
                    if (encrypted[i] != MAGIC[i]) {
                        throw new IOException("Saved login has an invalid format.");
                    }
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false),
                        new GCMParameterSpec(128, encrypted, MAGIC.length + 1, IV_LENGTH));
                cipher.updateAAD(AAD);
                plaintext = cipher.doFinal(encrypted, headerLength,
                        encrypted.length - headerLength);
                if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                    throw new IOException("Saved login exceeds the storage limit.");
                }
                JSONObject json = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                if (!(json.opt("version") instanceof Integer)
                        || json.getInt("version") != 1
                        || !(json.opt("session") instanceof JSONObject)) {
                    throw new IOException("Saved login has an invalid format.");
                }
                NeteaseApi.Session session = NeteaseApi.Session.fromJson(json.getJSONObject("session"));
                validate(session);
                return session;
            } catch (GeneralSecurityException | JSONException | RuntimeException error) {
                // Provider/JSON exceptions may include private material: do not chain them.
                throw new IOException("Saved login cannot be decrypted or is invalid. Sign in again.");
            } finally {
                erase(plaintext);
                erase(encrypted);
            }
        }
    }

    public void save(NeteaseApi.Session session) throws IOException {
        synchronized (LOCK) {
            validate(session);
            byte[] plaintext = null;
            byte[] encrypted = null;
            try {
                JSONObject json = new JSONObject();
                json.put("version", 1);
                json.put("session", session.toJson());
                plaintext = json.toString().getBytes(StandardCharsets.UTF_8);
                if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                    throw new IOException("Login exceeds the storage limit.");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                // Keystore supplies a fresh nonce; caller-provided encryption nonces are forbidden.
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(AAD);
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != IV_LENGTH) {
                    throw new IOException("Secure storage is unavailable.");
                }
                byte[] ciphertext = cipher.doFinal(plaintext);
                encrypted = new byte[MAGIC.length + 1 + IV_LENGTH + ciphertext.length];
                System.arraycopy(MAGIC, 0, encrypted, 0, MAGIC.length);
                encrypted[MAGIC.length] = (byte) IV_LENGTH;
                System.arraycopy(iv, 0, encrypted, MAGIC.length + 1, IV_LENGTH);
                System.arraycopy(ciphertext, 0, encrypted, MAGIC.length + 1 + IV_LENGTH,
                        ciphertext.length);
                writeAtomic(sessionFile, encrypted);
            } catch (GeneralSecurityException | JSONException | RuntimeException error) {
                throw new IOException("Login could not be saved securely.");
            } finally {
                erase(plaintext);
                erase(encrypted);
            }
        }
    }

    /** Deletes only this sample's saved NetEase Music login and its independent key. */
    public void clear() throws IOException {
        synchronized (LOCK) {
            boolean deletionFailed = false;
            try {
                sessionFile.delete();
                File base = sessionFile.getBaseFile();
                deletionFailed = base.exists()
                        || new File(base.getPath() + ".bak").exists()
                        || new File(base.getPath() + ".new").exists();
                // Remove the key even if a filesystem deletion failed: old ciphertext becomes unusable.
                KeyStore keyStore = keyStore();
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS);
                }
            } catch (GeneralSecurityException | RuntimeException error) {
                throw new IOException("Saved login could not be fully removed.");
            }
            if (deletionFailed) {
                throw new IOException("Saved login could not be fully removed.");
            }
        }
    }

    private static void validate(NeteaseApi.Session session) throws IOException {
        if (session == null || session.userId == null
                || !session.userId.matches("[1-9][0-9]{0,31}")
                || session.nickname == null || session.nickname.length() > 256) {
            throw new IOException("Login data is invalid.");
        }
        for (int i = 0; i < session.nickname.length(); i++) {
            if (Character.isISOControl(session.nickname.charAt(i))) {
                throw new IOException("Login data is invalid.");
            }
        }
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new IOException("Secure storage is unavailable.");
        }
        return keyStore;
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore keyStore = keyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (!(key instanceof SecretKey)) {
                throw new GeneralSecurityException("Invalid storage key.");
            }
            return (SecretKey) key;
        }
        if (!create) {
            throw new GeneralSecurityException("Storage key is unavailable.");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static boolean hasCommittedFile(AtomicFile file) {
        File base = file.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists();
    }

    private static byte[] readBounded(AtomicFile file, int limit) throws IOException {
        try (FileInputStream input = file.openRead();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - output.size()) {
                    throw new IOException("Private storage file exceeds its size limit.");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException error) {
            throw new IOException("Private storage could not be read.");
        }
    }

    private static void writeAtomic(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) {
                file.failWrite(output);
            }
            throw new IOException("Private storage could not be written.");
        }
    }

    private static void erase(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}


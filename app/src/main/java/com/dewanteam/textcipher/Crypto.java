package com.dewanteam.textcipher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
    public static final String PREFIX = "§";
    public static final String DEFAULT_KEY = "mojoen";
    public static final int MAX_PLAINTEXT_LENGTH = 1300;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final byte[] KDF_SALT = "pwc".getBytes(StandardCharsets.UTF_8);
    private static final int KDF_ROUNDS = 15000;
    private static final int KEY_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PACK_THRESHOLD = 100;
    private static final byte PLAIN_BODY = 0;
    private static final byte DEFLATED_BODY = 1;
    private static final int MAX_EXPANDED_BYTES = MAX_PLAINTEXT_LENGTH * 4;
    public static final int MAX_MEDIA_BYTES = 40 * 1024 * 1024;

    private static final String STORAGE_FILE = "textcipher_secret_store";
    private static final String STORAGE_VALUE = "encrypted_password";
    private static final String VAULT_ALIAS = "textcipher_passphrase_v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {

    }

    public static boolean looksEncrypted(String value) {
        return value != null && value.trim().startsWith(PREFIX);
    }

    public static SecretKey deriveKey(String password) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) throw new GeneralSecurityException("Enter a key first.");
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new GeneralSecurityException("key must be 128 characters or less.");
        }

        PBEKeySpec request = new PBEKeySpec(password.toCharArray(), KDF_SALT, KDF_ROUNDS, KEY_BITS);
        try {
            byte[] material = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(request)
                    .getEncoded();
            return new SecretKeySpec(material, "AES");
        } finally {
            request.clearPassword();
        }
    }

    public static String encrypt(String plaintext, String password) throws GeneralSecurityException {
        return encrypt(plaintext, deriveKey(password));
    }

    public static String encrypt(String plaintext, SecretKey key) throws GeneralSecurityException {
        checkText(plaintext, key);

        byte[] clear = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] packed = clear.length >= PACK_THRESHOLD ? deflate(clear) : null;
        boolean usePackedBody = packed != null && packed.length < clear.length;

        byte[] body = usePackedBody ? packed : clear;
        byte[] frame = new byte[body.length + 1];
        frame[0] = usePackedBody ? DEFLATED_BODY : PLAIN_BODY;
        System.arraycopy(body, 0, frame, 1, body.length);

        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] authenticated = cipher.doFinal(frame);

        return PREFIX + Base64.encodeToString(join(nonce, authenticated), Base64.NO_WRAP);
    }

    public static String decrypt(String message, String password) throws GeneralSecurityException {
        return decrypt(message, deriveKey(password));
    }

    public static String decrypt(String message, SecretKey key) throws GeneralSecurityException {
        if (message == null || key == null) throw new GeneralSecurityException("The message or key is missing.");

        String normalized = message.trim().replaceAll("\\s+", "");
        if (!normalized.startsWith(PREFIX)) throw new GeneralSecurityException("this ddoesnt seem right.");

        String encoded = normalized.substring(PREFIX.length());
        if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) {
            throw new GeneralSecurityException("The encrypted message is not valid Base64.");
        }
        if (encoded.length() % 4 == 1) throw new GeneralSecurityException("The encrypted message looks incomplete.");

        int missingPadding = (4 - encoded.length() % 4) % 4;
        StringBuilder padded = new StringBuilder(encoded);
        while (missingPadding-- > 0) {
            padded.append('=');
        }

        final byte[] frame;
        try {
            frame = Base64.decode(padded.toString(), Base64.NO_WRAP);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("The encrypted message is not valid Base64.", exception);
        }

        if (frame.length < NONCE_BYTES + TAG_BITS / 8 + 1) throw new GeneralSecurityException("The encrypted message is incomplete.");

        byte[] nonce = Arrays.copyOfRange(frame, 0, NONCE_BYTES);
        byte[] ciphertextAndTag = Arrays.copyOfRange(frame, NONCE_BYTES, frame.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));

        byte[] content;
        try {
            content = cipher.doFinal(ciphertextAndTag);
        } catch (GeneralSecurityException exception) {
            throw new GeneralSecurityException("key is wrong or message changed perhas?", exception);
        }

        if (content.length == 0) throw new GeneralSecurityException("The decrypted payload is empty.");

        byte kind = content[0];
        byte[] body = Arrays.copyOfRange(content, 1, content.length);
        byte[] clear;

        if (kind == PLAIN_BODY) clear = body;
        else if (kind == DEFLATED_BODY) clear = inflate(body);
        else throw new GeneralSecurityException("This message uses an unsupported payload format.");

        String result = new String(clear, StandardCharsets.UTF_8);
        if (result.length() > MAX_PLAINTEXT_LENGTH) {
            throw new GeneralSecurityException("The decrypted message exceeds the supported size limit.");
        }

        return result;
    }

    public static byte[] encryptMedia(byte[] clear, String password) throws GeneralSecurityException {
        return encryptMedia(clear, deriveKey(password));
    }

    public static byte[] encryptMedia(byte[] clear, SecretKey key) throws GeneralSecurityException {
        if (clear == null || key == null) throw new GeneralSecurityException("The file or key is missing.");
        if (clear.length > MAX_MEDIA_BYTES) throw new GeneralSecurityException("Choose a file smaller than 40 MB.");

        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        return join(nonce, cipher.doFinal(clear));
    }
    // Media format: dont know 12byte GCM nonce and a  tag.
    public static byte[] decryptMedia(byte[] encrypted, String password) throws GeneralSecurityException {
        return decryptMedia(encrypted, deriveKey(password));
    }

    public static byte[] decryptMedia(byte[] encrypted, SecretKey key) throws GeneralSecurityException {
        if (encrypted == null || key == null) throw new GeneralSecurityException("The file or key is missing.");
        if (encrypted.length > MAX_MEDIA_BYTES + NONCE_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("Encrypted media is larger than the 40 MB limit.");
        }
        if (encrypted.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("This file is too small to be a valid encrypted attachment.");
        }

        byte[] nonce = Arrays.copyOfRange(encrypted, 0, NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        try {
            byte[] clear = gcm.doFinal(sealed);
            if (clear.length > MAX_MEDIA_BYTES) throw new GeneralSecurityException("Decrypted media is larger than the 40 MB limit.");
            return clear;
        } catch (GeneralSecurityException exception) {
            throw new GeneralSecurityException("Could not decrypt this file. Check the key and file integrity.", exception);
        }
    }

    public static void savePassword(Context context, String password) throws Exception {
        if (password == null || password.isEmpty()) {
            clearPassword(context);
            return;
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new GeneralSecurityException("key must be 128 characters or less.");
        }

        Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
        wrap.init(Cipher.ENCRYPT_MODE, vaultKey());
        byte[] iv = wrap.getIV();
        byte[] sealed = wrap.doFinal(password.getBytes(StandardCharsets.UTF_8));
        String stored = Base64.encodeToString(join(iv, sealed), Base64.NO_WRAP);

        prefs(context).edit().putString(STORAGE_VALUE, stored).apply();
    }

    public static String loadPassword(Context context) {
        String stored = prefs(context).getString(STORAGE_VALUE, "");
        if (stored.isEmpty()) {
            try {
                savePassword(context, DEFAULT_KEY);
            } catch (Exception ignored) {
                // fall back to mojoen..
            }
            return DEFAULT_KEY;
        }

        try {
            byte[] data = Base64.decode(stored, Base64.NO_WRAP);
            if (data.length <= NONCE_BYTES) return "";

            byte[] iv = Arrays.copyOfRange(data, 0, NONCE_BYTES);
            byte[] sealed = Arrays.copyOfRange(data, NONCE_BYTES, data.length);

            Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
            unwrap.init(Cipher.DECRYPT_MODE, vaultKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(unwrap.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            try {
                savePassword(context, DEFAULT_KEY);
            } catch (Exception ignoredAgain) {

            }
            return DEFAULT_KEY;
        }
    }

    public static void clearPassword(Context context) {
        prefs(context).edit().remove(STORAGE_VALUE).apply();
    }

    private static void checkText(String text, SecretKey key) throws GeneralSecurityException {
        if (text == null || text.trim().isEmpty()) throw new GeneralSecurityException("Enter a message to encrypt.");
        if (text.length() > MAX_PLAINTEXT_LENGTH) throw new GeneralSecurityException("Messages must be 1,300 characters or fewer.");
        if (text.startsWith(PREFIX)) throw new GeneralSecurityException("This message already starts with the § marker.");
        if (key == null) throw new GeneralSecurityException("The key is unavailable.");
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static byte[] deflate(byte[] source) {
        Deflater compressor = new Deflater(Deflater.BEST_SPEED, true);
        try {
            compressor.setInput(source);
            compressor.finish();

            ByteArrayOutputStream result = new ByteArrayOutputStream(source.length);
            byte[] chunk = new byte[768];
            while (!compressor.finished()) {
                int size = compressor.deflate(chunk);
                if (size <= 0) break;
                result.write(chunk, 0, size);
            }

            return result.toByteArray();
        } finally {
            compressor.end();
        }
    }

    private static byte[] inflate(byte[] source) throws GeneralSecurityException {
        Inflater expander = new Inflater(true);
        try {
            expander.setInput(source);

            ByteArrayOutputStream result = new ByteArrayOutputStream(Math.max(source.length * 2, 64));
            byte[] chunk = new byte[1024];

            while (!expander.finished()) {
                int size = expander.inflate(chunk);
                if (size > 0) {
                    result.write(chunk, 0, size);
                    if (result.size() > MAX_EXPANDED_BYTES) throw new GeneralSecurityException("The compressed message is too large.");
                    continue;
                }

                if (expander.needsInput() || expander.needsDictionary()) break;

                throw new GeneralSecurityException("The compressed message could not be expanded.");
            }

            if (!expander.finished()) throw new GeneralSecurityException("The compressed message is incomplete.");

            return result.toByteArray();
        } catch (DataFormatException exception) {
            throw new GeneralSecurityException("The compressed message could not be expanded.", exception);
        } finally {
            expander.end();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORAGE_FILE, Context.MODE_PRIVATE);
    }

    private static SecretKey vaultKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);

        if (store.containsAlias(VAULT_ALIAS)) return (SecretKey) store.getKey(VAULT_ALIAS, null);

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                VAULT_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());

        return generator.generateKey();
    }
}

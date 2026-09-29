package com.iot.viewplustest.data.auth;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound encrypted token; passwords are never stored. */
public final class AuthStore {
    private static final String ALIAS = "viewplus_session_key";
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static void save(Context context, String token) throws Exception {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("Missing token");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences("viewplus_auth", Context.MODE_PRIVATE).edit()
                .clear().putString("ciphertext", encrypted)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Session save failed");
    }
    public static String load(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("viewplus_auth", Context.MODE_PRIVATE);
        String encrypted = prefs.getString("ciphertext", null);
        if (encrypted == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { clear(context); return null; }
    }
    public static void clear(Context context) {
        context.getSharedPreferences("viewplus_auth", Context.MODE_PRIVATE).edit().clear().commit();
    }
}

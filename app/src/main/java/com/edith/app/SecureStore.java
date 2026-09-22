package com.edith.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/** Stockage chiffré (Android Keystore) pour les clés API. */
final class SecureStore {
    private final SharedPreferences prefs;

    SecureStore(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    ctx,
                    "edith_secure",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Stockage chiffré indisponible : " + e.getMessage(), e);
        }
    }

    String get(String key, String def) {
        return prefs.getString(key, def);
    }

    void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }
}

package com.example.wifibluetoothrecorder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object DatabaseKey {
    @Synchronized fun password(context: Context): String {
        val prefs = context.getSharedPreferences("database_key", Context.MODE_PRIVATE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "PocketDriver.database"
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val saved = prefs.getString("wrapped", null)
        if (saved != null) {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
        check(!context.getDatabasePath("pocketdriver.db").exists()) { "Database key is missing; existing database was preserved." }
        val password = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val wrapped = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit())
        return password
    }
}

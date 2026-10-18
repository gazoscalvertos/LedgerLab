package com.ledgerlab.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings. API keys are encrypted with the same Keystore-backed cipher as wallet secrets. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ledgerlab-settings", Context.MODE_PRIVATE)
    private val cipher by lazy { SecretCipher("ledgerlab-settings") }

    private val _hasPinataKey = MutableStateFlow(prefs.contains(KEY_PINATA))
    val hasPinataKey: StateFlow<Boolean> = _hasPinataKey.asStateFlow()

    fun pinataJwt(): String? = prefs.getString(KEY_PINATA, null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    fun setPinataJwt(jwt: String?) {
        if (jwt.isNullOrBlank()) prefs.edit().remove(KEY_PINATA).apply()
        else prefs.edit().putString(KEY_PINATA, cipher.encrypt(jwt.trim())).apply()
        _hasPinataKey.value = !jwt.isNullOrBlank()
    }

    private companion object {
        const val KEY_PINATA = "pinata-jwt"
    }
}

package com.ledgerlab.app.data

import android.content.Context
import com.ledgerlab.xrpl.wallet.KeyAlgorithm
import com.ledgerlab.xrpl.wallet.WalletKeyInfo
import com.ledgerlab.xrpl.wallet.WalletKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** A wallet saved on this device. The secret is only ever stored encrypted. */
data class StoredWallet(
    val id: String,
    val label: String,
    val address: String,
    val algorithm: KeyAlgorithm,
    val encryptedSecret: String,
    val createdAt: Long,
)

/**
 * The device's wallets. Keys work on every network; each network just has its own account state.
 */
class WalletStore(context: Context) {
    private val prefs = context.getSharedPreferences("ledgerlab-wallets", Context.MODE_PRIVATE)
    private val cipher by lazy { SecretCipher() }

    private val _wallets = MutableStateFlow(load())
    val wallets: StateFlow<List<StoredWallet>> = _wallets.asStateFlow()

    private val _selectedId = MutableStateFlow(prefs.getString(KEY_SELECTED, null) ?: _wallets.value.firstOrNull()?.id)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun create(label: String, algorithm: KeyAlgorithm): StoredWallet = add(label, WalletKeys.generate(algorithm))

    /** Throws [IllegalArgumentException] for an invalid secret or one that's already saved. */
    fun import(label: String, secret: String): StoredWallet {
        val keys = WalletKeys.fromSecret(secret)
        require(_wallets.value.none { it.address == keys.address }) { "That wallet is already saved." }
        return add(label, keys)
    }

    fun select(id: String) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun delete(id: String) {
        _wallets.value = _wallets.value.filterNot { it.id == id }
        save()
        if (_selectedId.value == id) _wallets.value.firstOrNull()?.let { select(it.id) } ?: clearSelection()
    }

    fun secretOf(wallet: StoredWallet): String = cipher.decrypt(wallet.encryptedSecret)

    private fun add(label: String, keys: WalletKeyInfo): StoredWallet {
        val wallet = StoredWallet(
            id = UUID.randomUUID().toString(),
            label = label.ifBlank { "Wallet ${_wallets.value.size + 1}" },
            address = keys.address,
            algorithm = keys.algorithm,
            encryptedSecret = cipher.encrypt(keys.secret),
            createdAt = System.currentTimeMillis(),
        )
        _wallets.value = _wallets.value + wallet
        save()
        select(wallet.id)
        return wallet
    }

    private fun clearSelection() {
        _selectedId.value = null
        prefs.edit().remove(KEY_SELECTED).apply()
    }

    private fun save() {
        val array = buildJsonArray {
            _wallets.value.forEach { w ->
                add(
                    buildJsonObject {
                        put("id", w.id)
                        put("label", w.label)
                        put("address", w.address)
                        put("algorithm", w.algorithm.name)
                        put("secret", w.encryptedSecret)
                        put("createdAt", w.createdAt)
                    },
                )
            }
        }
        prefs.edit().putString(KEY_WALLETS, array.toString()).apply()
    }

    private fun load(): List<StoredWallet> {
        val raw = prefs.getString(KEY_WALLETS, null) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (o[key] as? JsonPrimitive)?.content
            StoredWallet(
                id = s("id") ?: return@mapNotNull null,
                label = s("label") ?: "Wallet",
                address = s("address") ?: return@mapNotNull null,
                algorithm = runCatching { KeyAlgorithm.valueOf(s("algorithm")!!) }.getOrDefault(KeyAlgorithm.ED25519),
                encryptedSecret = s("secret") ?: return@mapNotNull null,
                createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    private companion object {
        const val KEY_WALLETS = "wallets"
        const val KEY_SELECTED = "selected"
    }
}

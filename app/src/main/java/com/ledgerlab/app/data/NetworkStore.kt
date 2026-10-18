package com.ledgerlab.app.data

import android.content.Context
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The network the whole app is pointed at, remembered between launches. */
class NetworkStore(context: Context) {
    private val prefs = context.getSharedPreferences("ledgerlab", Context.MODE_PRIVATE)
    private val _network = MutableStateFlow(XrplNetwork.fromName(prefs.getString(KEY, null)))
    val network: StateFlow<XrplNetwork> = _network.asStateFlow()

    fun select(network: XrplNetwork) {
        _network.value = network
        prefs.edit().putString(KEY, network.name).apply()
    }

    private companion object {
        const val KEY = "network"
    }
}

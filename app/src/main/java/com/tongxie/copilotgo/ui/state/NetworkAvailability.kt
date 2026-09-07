package com.tongxie.copilotgo.ui.state

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.tongxie.copilotgo.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

enum class NetworkAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Physical connectivity is advisory; it is not proof of API or proxy health. */
fun observeNetworkAvailability(context: Context): Flow<NetworkAvailability> = callbackFlow {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        Logger.w("Connectivity service is unavailable")
        trySend(NetworkAvailability.UNKNOWN)
        close()
        return@callbackFlow
    }
    fun publish() {
        val state = try {
            if (manager.activeNetwork == null) NetworkAvailability.UNAVAILABLE else NetworkAvailability.AVAILABLE
        } catch (_: SecurityException) {
            Logger.w("Connectivity observation was denied")
            NetworkAvailability.UNKNOWN
        }
        trySend(state)
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onUnavailable() = publish()
    }
    try {
        manager.registerDefaultNetworkCallback(callback)
    } catch (_: SecurityException) {
        Logger.w("Connectivity callback registration was denied")
        trySend(NetworkAvailability.UNKNOWN)
        close()
        return@callbackFlow
    }
    publish()
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged().flowOn(Dispatchers.Default)

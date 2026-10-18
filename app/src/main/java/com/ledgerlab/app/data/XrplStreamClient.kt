package com.ledgerlab.app.data

import com.ledgerlab.xrpl.StreamEvent
import com.ledgerlab.xrpl.StreamParser
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Streams validated ledgers and transactions from a public rippled server, reconnecting forever. */
class XrplStreamClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    fun events(network: XrplNetwork): Flow<StreamEvent> = flow {
        var attempt = 0
        while (true) {
            try {
                socket(network).collect { event ->
                    if (event is StreamEvent.Connected) attempt = 0
                    emit(event)
                }
            } catch (e: IOException) {
                emit(StreamEvent.Disconnected(e.message))
            }
            attempt++
            delay(minOf(15_000L, 1_000L shl minOf(attempt, 4)))
        }
    }

    private fun socket(network: XrplNetwork): Flow<StreamEvent> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(StreamEvent.Connected)
                webSocket.send(StreamParser.SUBSCRIBE_REQUEST)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                StreamParser.parse(text)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("Server closed the connection ($code)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IOException(t.message ?: "Connection failed", t))
            }
        }
        val socket = http.newWebSocket(Request.Builder().url(network.wsUrl).build(), listener)
        awaitClose { socket.cancel() }
    }.buffer(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

package com.activeliang.actioncable.client.kotlin

import okhttp3.*
import java.io.IOException
import java.net.CookieHandler
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

class Connection constructor(
        private val uri: URI,
        private val options: Options
) {
    /**
     * Options for connection.
     *
     * @property sslContext SSLContext
     * @property hostnameVerifier HostnameVerifier
     * @property cookieHandler CookieHandler
     * @property query Query parameters to send on handshake.
     * @property headers HTTP Headers to send on handshake.
     * @property reconnection Whether to reconnect automatically. If reconnection is true, the client attempts to reconnect to the server when underlying connection is stale.
     * @property reconnectionMaxAttempts The maximum number of attempts to reconnect.
     * @property reconnectionDelay First delay seconds of reconnection.
     * @property reconnectionDelayMax Max delay seconds of reconnection.
     * @property webSocketFactory To use your own OkHttp WebSocket.Factory, set this option.
     */
    data class Options(
            var sslContext: SSLContext? = null,
            var hostnameVerifier: HostnameVerifier? = null,
            var cookieHandler: CookieHandler? = null,
            var query: Map<String, String>? = null,
            var headers: Map<String, String>? = null,
            var reconnection: Boolean = false,
            var reconnectionMaxAttempts: Int = 30,
            var reconnectionDelay: Int = 3,
            var reconnectionDelayMax: Int = 30,
            var webSocketFactory: WebSocket.Factory? = null
    )

    private enum class State {
        CONNECTING,
        OPEN,
        CLOSING,
        CLOSED,
        TERMINATING
    }

    var onOpen: suspend () -> Unit = {}
    var onMessage: suspend (jsonString: String) -> Unit = {}
    var onClose: () -> Unit = {}
    var onFailure: (e: Throwable) -> Unit = {}

    private var state = State.CONNECTING

    private var webSocket: WebSocket? = null

    private var isReopening = false

    private val eventsHandler = EventsHandler()

    fun open() {
        eventsHandler.handle(::performOpen)
    }

    fun terminate() {
        eventsHandler.handle {
            state = State.TERMINATING
            performClose()
        }
    }

    private suspend fun performOpen() {
        if (isOpen()) {
            fireOnFailure(IllegalStateException("Must close existing connection before opening"))
        } else {
            doOpen()
        }
    }

    private suspend fun performClose() {
        webSocket?.let { webSocket ->
            if (!isState(State.CLOSING, State.CLOSED)) {
                try {
                    webSocket.close(1000, "connection closed manually")
                    if (state != State.TERMINATING) state = State.CLOSING
                } catch (e: IOException) {
                    fireOnFailure(e)
                } catch (e: IllegalStateException) {
                    fireOnFailure(e)
                }
            }
        }
    }

    fun reopen() {
        if (isState(State.TERMINATING)) return
        if (isState(State.CLOSED)) {
            open()
        } else {
            isReopening = true
            eventsHandler.handle(::performClose)
        }
    }

    fun send(data: Any): Boolean {
        if (!isOpen()) return false

        eventsHandler.handle { doSend(data = data) }

        return true
    }

    private fun isState(vararg states: State) = states.contains(state)

    private fun isOpen() = webSocket?.let { isState(State.OPEN) } ?: false

    private fun doOpen() {
        state = State.CONNECTING

        val urlBuilder = StringBuilder(uri.toString())

        options.query?.let { urlBuilder.append("?${it.toQueryString()}") }

        val requestBuilder = Request.Builder().url(urlBuilder.toString())

        options.headers?.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

        val request = requestBuilder.build()

        val webSocketFactory = options.webSocketFactory ?: OkHttpClient.Builder().apply {
            options.sslContext?.let { sslSocketFactory(it.socketFactory) }
            options.hostnameVerifier?.let { hostnameVerifier(it) }
        }.build()

        webSocketFactory.newWebSocket(request, webSocketListener)
    }

    private suspend fun doSend(data: Any) {
        webSocket?.let { webSocket ->
            try {
                webSocket.send((data as Command).toJsonString())
            } catch (e: IOException) {
                fireOnFailure(e)
            }
        }
    }

    private fun fireOnFailure(error: Throwable) {
        onFailure.invoke(error)
        if (isState(State.TERMINATING)) stopEventsHandler()
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(openedWebSocket: WebSocket, response: Response) {
            state = State.OPEN
            webSocket = openedWebSocket
            eventsHandler.handle { onOpen.invoke() }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            eventsHandler.handle { handleFailure(throwable) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            eventsHandler.handle {
                onMessage(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            eventsHandler.handle(::handleClosure)
        }
    }

    private suspend fun handleFailure(throwable: Throwable) {
        state = State.CLOSED
        fireOnFailure(throwable)
    }

    private suspend fun handleClosure() {
        if (isState(State.TERMINATING)) {
            stopEventsHandler()
            isReopening = false
        }
        state = State.CLOSED

        onClose.invoke()

        if (isReopening) {
            isReopening = false
            open()
        }
    }

    private fun stopEventsHandler() {
        eventsHandler.stop()
    }
}

private fun Map<String, String>.toQueryString(): String {
    return this.keys.asSequence().mapNotNull { key ->
        this[key]?.let {
            "$key=${URLEncoder.encode(this[key], "UTF-8")}"
        }
    }.joinToString("&")
}

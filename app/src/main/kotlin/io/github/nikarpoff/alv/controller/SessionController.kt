package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.model.protocol.ClientMessage
import io.github.nikarpoff.alv.model.protocol.Frame
import io.github.nikarpoff.alv.model.protocol.Hello
import io.github.nikarpoff.alv.model.protocol.ProtocolCodec
import io.github.nikarpoff.alv.model.protocol.ProtocolException
import io.github.nikarpoff.alv.model.protocol.ServerMessage
import io.github.nikarpoff.alv.model.protocol.Welcome
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URI
import java.util.concurrent.TimeUnit

/** Session with the gateway (PROTO-1): one long-lived WebSocket while the app is on the screen. */
interface Session {
    /** Events of the session, called from any thread. */
    interface Listener {
        /** `welcome` came: the session is ready, [Welcome.config] applies (PROTO-13). */
        fun onWelcome(welcome: Welcome)

        /** A message of the gateway other than `welcome`. */
        fun onMessage(message: ServerMessage)

        /** A binary frame of the gateway. */
        fun onFrame(frame: Frame)

        /** The connection is lost or closed. */
        fun onDisconnected()
    }

    /** `welcome` came and the connection is still open. */
    val connected: Boolean

    /** Connects to [url] with [token]; [hello] builds the first message. Reconnects on its own until [close]. */
    fun open(url: String, token: String, hello: () -> Hello)

    /** Closes the connection and stops reconnecting. */
    fun close()

    /** Sends a message; `false` when there is no connection. */
    fun send(message: ClientMessage): Boolean

    /** Sends a binary frame; `false` when there is no connection. */
    fun send(frame: Frame): Boolean
}

/** Address of the session endpoint from the setting (MOB-9). */
object SessionUrl {
    /** Path of the endpoint (PROTO-1). */
    const val PATH = "/v1/session"

    /**
     * `ws://` or `wss://` address of the endpoint: an address without a path gets [PATH].
     *
     * @return `null` for an empty or malformed address or another scheme
     */
    fun resolve(setting: String): String? {
        val text = setting.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.scheme != "ws" && uri.scheme != "wss") return null
        if (uri.host.isNullOrEmpty()) return null
        val path = uri.rawPath.orEmpty()
        return if (path.isEmpty() || path == "/") text.trimEnd('/') + PATH else text
    }
}

/**
 * WebSocket session over OkHttp (PROTO-1, PROTO-2, PROTO-9, MOB-8).
 *
 * Subprotocol `alv.v1` and `Authorization: Bearer <token>`; `hello` right after the handshake.
 * The certificate is checked against the system roots only. A lost connection is reported and
 * retried after the pauses of [ReconnectPolicy]; `welcome` resets them. Close code 4001
 * (`unsupported_protocol`) stops the retries: a newer app is needed.
 *
 * @param listener receives the events; set before [open]
 */
class SessionController(
    private val loop: Loop,
    private val client: OkHttpClient = defaultClient(),
    private val policy: ReconnectPolicy = ReconnectPolicy(),
) : Session {
    var listener: Session.Listener? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    override var connected: Boolean = false
        private set

    private class Target(val url: String, val token: String, val hello: () -> Hello)

    private var target: Target? = null
    private var generation = 0
    private var retry: Cancellable? = null

    override fun open(url: String, token: String, hello: () -> Hello) = loop.post {
        drop()
        target = Target(url, token, hello)
        policy.onConnected()
        connect()
    }

    override fun close() = loop.post {
        drop()
        target = null
    }

    override fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        return connected && ws.send(ProtocolCodec.encodeMessage(message))
    }

    override fun send(frame: Frame): Boolean {
        val ws = socket ?: return false
        return connected && ws.send(ProtocolCodec.encodeFrame(frame).toByteString())
    }

    private fun drop() {
        generation++
        retry?.cancel()
        retry = null
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        if (connected) {
            connected = false
            listener?.onDisconnected()
        }
    }

    private fun connect() {
        val current = target ?: return
        val request = try {
            Request.Builder()
                .url(current.url)
                .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                .apply { if (current.token.isNotEmpty()) header("Authorization", "Bearer ${current.token}") }
                .build()
        } catch (e: IllegalArgumentException) {
            lost(generation, retryable = true)
            return
        }
        socket = client.newWebSocket(request, Callback(generation, current))
    }

    /** The connection of [gen] is gone: report it and retry unless [retryable] is false. */
    private fun lost(gen: Int, retryable: Boolean) {
        if (gen != generation) return
        generation++
        socket = null
        val wasConnected = connected
        connected = false
        if (wasConnected) listener?.onDisconnected()
        if (!retryable || target == null) return
        val next = generation
        retry = loop.schedule(policy.nextDelayMs()) {
            if (next == generation) connect()
        }
    }

    private inner class Callback(private val gen: Int, private val target: Target) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ProtocolCodec.encodeMessage(target.hello()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                ProtocolCodec.decodeServerMessage(text)
            } catch (e: ProtocolException) {
                return
            }
            if (message is Welcome) {
                loop.post {
                    if (gen != generation) return@post
                    connected = true
                    policy.onConnected()
                    listener?.onWelcome(message)
                }
            } else {
                loop.post { if (gen == generation) listener?.onMessage(message) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = try {
                ProtocolCodec.decodeFrame(bytes.toByteArray())
            } catch (e: ProtocolException) {
                return
            }
            loop.post { if (gen == generation) listener?.onFrame(frame) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            loop.post { lost(gen, retryable = code != CLOSE_UNSUPPORTED_PROTOCOL) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            loop.post { lost(gen, retryable = code != CLOSE_UNSUPPORTED_PROTOCOL) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            loop.post { lost(gen, retryable = true) }
        }
    }

    companion object {
        /** WebSocket subprotocol (PROTO-1). */
        const val SUBPROTOCOL = "alv.v1"

        /** Close code of an unsupported protocol version (PROTO-10). */
        const val CLOSE_UNSUPPORTED_PROTOCOL = 4001

        private const val NORMAL_CLOSURE = 1000

        /** Interval of the client ping: a dead network is noticed without waiting for a write. */
        private const val PING_INTERVAL_S = 15L

        /**
         * OkHttp client: system trust roots only, no message compression (PROTO-1),
         * no read timeout for the long-lived socket.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .minWebSocketMessageToCompress(Long.MAX_VALUE)
            .build()
    }
}

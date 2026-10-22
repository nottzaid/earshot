package dev.nottzaid.earshot.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

const val PROTO_VERSION = 1
private const val DISCOVERY_PORT = 10005
private const val PROBE = "EARSHOT?1"

/** A PC that answered a discovery probe. [pairedWith] is the phone id it trusts, if any. */
data class FoundPc(val address: String, val name: String, val port: Int, val pairedWith: String?)

/** What the PC reports about its side of the mic. */
data class PcState(
    val listening: Boolean = false,
    val apps: List<String> = emptyList(),
    val muted: Boolean = false,
    val isDefault: Boolean = false,
    val mode: String? = null,
    val receiverUp: Boolean = false,
)

sealed interface ControlEvent {
    data class Welcome(val name: String, val secret: String) : ControlEvent
    data class Denied(val reason: String) : ControlEvent
    data class State(val state: PcState) : ControlEvent
    data class Rtt(val ms: Float) : ControlEvent
}

object Discovery {
    /**
     * Probes [hint] directly and the LAN by broadcast, collecting answers for [windowMs].
     * Several PCs may answer; callers pick.
     */
    suspend fun probe(hint: String?, windowMs: Long = 1200): List<FoundPc> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, FoundPc>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 150
            val payload = PROBE.toByteArray()
            val targets = buildList {
                hint?.let { runCatching { add(InetAddress.getByName(it)) } }
                addAll(broadcastAddresses())
                runCatching { add(InetAddress.getByName("255.255.255.255")) }
            }.distinct()
            val deadline = System.nanoTime() + windowMs * 1_000_000
            var nextSend = 0L
            val buf = ByteArray(2048)
            while (System.nanoTime() < deadline && isActive) {
                if (System.nanoTime() >= nextSend) {
                    for (t in targets) runCatching { sock.send(DatagramPacket(payload, payload.size, t, DISCOVERY_PORT)) }
                    nextSend = System.nanoTime() + 400_000_000
                }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val msg = runCatching { JSONObject(String(pkt.data, 0, pkt.length)) }.getOrNull() ?: continue
                if (msg.optString("t") != "here" || msg.optInt("v") != PROTO_VERSION) continue
                val addr = pkt.address.hostAddress ?: continue
                found[addr] = FoundPc(
                    address = addr,
                    name = msg.optString("host", addr),
                    port = msg.optInt("port", 10004),
                    pairedWith = msg.optString("paired").takeIf { msg.has("paired") && !msg.isNull("paired") },
                )
                if (addr == hint) break // the PC we know answered: no need to wait out the window
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}

/**
 * One control connection. Runs until the socket drops or the caller is cancelled;
 * [outgoing] carries messages to send, [onEvent] gets everything the PC says.
 */
suspend fun runControlSession(
    pc: FoundPc,
    hello: JSONObject,
    outgoing: ReceiveChannel<JSONObject>,
    onEvent: suspend (ControlEvent) -> Unit,
) = withContext(Dispatchers.IO) {
    Socket().use { sock ->
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(pc.address, pc.port), 3000)
        sock.soTimeout = 1000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
        val lock = Any()
        fun send(msg: JSONObject) = synchronized(lock) {
            writer.write(msg.toString())
            writer.write("\n")
            writer.flush()
        }

        send(hello)
        coroutineScope {
            val pings = mutableMapOf<Int, Long>()
            var lastHeard = System.nanoTime()
            val sender = launch {
                for (msg in outgoing) send(msg)
            }
            val pinger = launch {
                var n = 0
                while (isActive) {
                    synchronized(pings) { pings[++n] = System.nanoTime() }
                    send(JSONObject().put("t", "ping").put("n", n))
                    delay(2000)
                }
            }
            try {
                while (isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (_: SocketTimeoutException) {
                        if (System.nanoTime() - lastHeard > 7_000_000_000) break // PC went quiet
                        continue
                    } ?: break
                    lastHeard = System.nanoTime()
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (msg.optString("t")) {
                        "welcome" -> onEvent(ControlEvent.Welcome(msg.optString("host"), msg.optString("secret")))
                        "denied" -> {
                            onEvent(ControlEvent.Denied(msg.optString("reason")))
                            break
                        }
                        "state" -> onEvent(ControlEvent.State(msg.toPcState()))
                        "pong" -> {
                            val sent = synchronized(pings) { pings.remove(msg.optInt("n")) }
                            if (sent != null) onEvent(ControlEvent.Rtt((System.nanoTime() - sent) / 1e6f))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // socket error: fall through and end the session
            } finally {
                sender.cancel()
                pinger.cancel()
            }
        }
    }
}

private fun JSONObject.toPcState() = PcState(
    listening = optBoolean("listening"),
    apps = optJSONArray("apps")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
    muted = optBoolean("muted"),
    isDefault = optBoolean("default"),
    mode = optString("mode").ifEmpty { null },
    receiverUp = optString("receiver") == "up",
)

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((k, v) in pairs) put(k, if (v is List<*>) JSONArray(v) else v)
}

@file:OptIn(ExperimentalStdlibApi::class)

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
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val PROTO_VERSION = 2
private const val DISCOVERY_PORT = 10005
private const val PROBE = "EARSHOT?2"

/** A PC that answered a discovery probe. [id] is its stable identity; [pairable] means no phone is paired yet. */
data class FoundPc(val address: String, val id: String, val name: String, val port: Int, val pairable: Boolean)

/** What the PC reports about its side of the mic. */
data class PcState(
    val listening: Boolean = false,
    val apps: List<String> = emptyList(),
    val muted: Boolean = false,
    /** The phone's last mute request the PC had applied when it sent this; older values are stale. */
    val muteSeq: Int = 0,
    val isDefault: Boolean = false,
    val mode: String? = null,
    val receiverUp: Boolean = false,
)

sealed interface ControlEvent {
    /** Trusted session established. [secret] is set only right after pairing. */
    data class Welcome(val pcId: String, val name: String, val secret: String?) : ControlEvent
    data class Denied(val reason: String) : ControlEvent
    data object Unpaired : ControlEvent
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
        // No network at all (between Wi-Fi networks, say) makes even creating the socket fail:
        // that's "found nothing", and the caller probes again.
        try { DatagramSocket().use { sock ->
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
                val id = msg.optString("pc").takeIf { it.isNotEmpty() } ?: continue
                found[addr] = FoundPc(
                    address = addr,
                    id = id,
                    name = msg.optString("host", addr),
                    port = msg.optInt("port", 10004),
                    pairable = msg.optBoolean("pairable"),
                )
                if (addr == hint) break // the PC we know answered: no need to wait out the window
            }
        } } catch (_: IOException) {
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

/** What the phone knows about the PC it trusts. */
data class Trust(val pcId: String, val secret: String)

class HandshakeException(val reason: String) : Exception(reason)

/**
 * One control connection. With [trust], both sides prove they hold the pairing secret
 * (the PC first, so the phone never talks to an impostor); without it, this pairs with
 * [pc], which only happens on the user's explicit request.
 * Runs until the socket drops or the caller is cancelled; [outgoing] carries messages
 * to send, [onEvent] gets everything the PC says.
 */
suspend fun runControlSession(
    pc: FoundPc,
    hello: JSONObject,
    trust: Trust?,
    outgoing: ReceiveChannel<JSONObject>,
    onEvent: suspend (ControlEvent) -> Unit,
) = withContext(Dispatchers.IO) {
    Socket().use { sock ->
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(pc.address, pc.port), 3000)
        sock.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
        val lock = Any()
        fun send(msg: JSONObject) = synchronized(lock) {
            writer.write(msg.toString())
            writer.write("\n")
            writer.flush()
        }
        fun read(): JSONObject = JSONObject(reader.readLine() ?: throw HandshakeException("closed"))

        // -- handshake
        val phoneNonce = randomHex(16)
        send(hello.put("nonce", phoneNonce).put("pair", trust == null))
        val first = read()
        when (first.optString("t")) {
            "denied" -> throw HandshakeException(first.optString("reason"))
            "paired" -> {
                if (trust != null) throw HandshakeException("unexpected")
                onEvent(ControlEvent.Welcome(first.optString("pc"), first.optString("host"), first.optString("secret")))
            }
            "challenge" -> {
                if (trust == null || first.optString("pc") != trust.pcId) throw HandshakeException("wrong-pc")
                val pcNonce = first.optString("nonce")
                val expected = proof(trust.secret, "pc", phoneNonce, pcNonce)
                if (!MessageDigest.isEqual(expected.toByteArray(), first.optString("proof").toByteArray())) {
                    throw HandshakeException("bad-proof")
                }
                send(JSONObject().put("t", "auth").put("proof", proof(trust.secret, "phone", pcNonce, phoneNonce)))
                val welcome = read()
                if (welcome.optString("t") != "welcome") throw HandshakeException(welcome.optString("reason", "denied"))
                onEvent(ControlEvent.Welcome(trust.pcId, first.optString("host", pc.name), null))
            }
            else -> throw HandshakeException("unexpected")
        }

        sock.soTimeout = 1000
        coroutineScope {
            val pings = mutableMapOf<Int, Long>()
            var lastHeard = System.nanoTime()
            val sender = launch {
                for (msg in outgoing) send(msg)
            }
            val pinger = launch {
                var n = 0
                while (isActive) {
                    synchronized(pings) {
                        pings[++n] = System.nanoTime()
                        pings.keys.removeAll { it < n - 8 } // unanswered pings don't pile up
                    }
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
                        "denied" -> {
                            onEvent(ControlEvent.Denied(msg.optString("reason")))
                            break
                        }
                        "unpaired" -> {
                            onEvent(ControlEvent.Unpaired)
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

private fun proof(secretHex: String, role: String, first: String, second: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secretHex.hexToByteArray(), "HmacSHA256"))
    return mac.doFinal("earshot/$PROTO_VERSION/$role/$first/$second".toByteArray()).toHexString()
}

private fun randomHex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.toHexString()

private fun JSONObject.toPcState() = PcState(
    listening = optBoolean("listening"),
    apps = optJSONArray("apps")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
    muted = optBoolean("muted"),
    muteSeq = optInt("mute_seq"),
    isDefault = optBoolean("default"),
    mode = optString("mode").ifEmpty { null },
    receiverUp = optString("receiver") == "up",
)

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((k, v) in pairs) put(k, if (v is List<*>) JSONArray(v) else v)
}

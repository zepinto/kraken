package com.zepinto.kraken

/**
 * How the two phones talk. The captain phone listens ([HostLink]) and the table phone connects ([ClientLink]).
 * There are two transports behind these interfaces: Nearby Connections and a plain socket on the same Wi-Fi.
 * Callbacks may arrive on any thread; the view model moves them to the main thread.
 */
interface HostLink {
    fun start(listener: HostListener)

    /** Send to every connected table phone. */
    fun send(text: String)
    fun stop()
}

interface HostListener {
    /** A phone wants to connect. [digits] is the code both phones show (Nearby); null for Wi-Fi, which uses a PIN instead. */
    fun onPairingRequest(peerId: String, peerName: String, digits: String?, accept: () -> Unit, reject: () -> Unit)
    fun onConnected(peerId: String, peerName: String)
    fun onMessage(peerId: String, text: String)
    fun onDisconnected(peerId: String)
    fun onError(message: String)
}

enum class LinkError { UNREACHABLE, DENIED, OTHER }

interface ClientLink {
    /** Start looking for captain phones (Nearby) or just register the listener (Wi-Fi). */
    fun start(listener: ClientListener)

    /** [target] is a Nearby endpoint id, or "address:port" for Wi-Fi. [pin] is only used by Wi-Fi. */
    fun connect(target: String, pin: String?)
    fun send(text: String)
    fun stop()
}

interface ClientListener {
    fun onFound(id: String, name: String)
    fun onLost(id: String)

    /** The code both phones show while a Nearby connection is being confirmed. */
    fun onPairing(digits: String?)
    fun onConnected()
    fun onMessage(text: String)
    fun onDisconnected(reason: String)
    fun onError(kind: LinkError, detail: String)
}

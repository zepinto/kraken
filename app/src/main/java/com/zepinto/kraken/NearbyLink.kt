package com.zepinto.kraken

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/** The runtime permissions Nearby Connections needs on this Android version. */
object NearbyPermissions {
    fun required(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        // Android 12 refuses a request for fine location unless coarse location is asked for in the same request.
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** On Android 12 the user may grant only approximate location; the rest must all be granted. */
    fun granted(context: Context) = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED ||
            (it == Manifest.permission.ACCESS_COARSE_LOCATION && Build.VERSION.SDK_INT >= 31)
    }
}

private const val SERVICE_ID = "com.zepinto.kraken"

/** Strategy STAR: one advertiser (the captain phone) with discoverers (table phones) connecting to it. */
private val STRATEGY = Strategy.P2P_STAR

/** Starting an advertisement or discovery that is already running is fine, not an error. */
private fun isAlreadyRunning(e: Exception) =
    (e as? ApiException)?.statusCode.let { it == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || it == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING }

/** [deviceName] identifies this phone to the other one, so it must be different for two phones of the same model. */
class NearbyHostLink(context: Context, private val deviceName: String) : HostLink {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val names = HashMap<String, String>()
    private val connected = LinkedHashSet<String>()
    private var listener: HostListener? = null

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener?.onMessage(endpointId, String(bytes, Charsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            listener?.onPairingRequest(
                endpointId, info.endpointName, info.authenticationDigits,
                accept = { client.acceptConnection(endpointId, payloads) },
                reject = { client.rejectConnection(endpointId) },
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                synchronized(connected) { connected += endpointId }
                listener?.onConnected(endpointId, names[endpointId] ?: "Phone")
            } else {
                // a rejected or failed pairing: let the screen drop its dialog
                listener?.onDisconnected(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            synchronized(connected) { connected -= endpointId }
            listener?.onDisconnected(endpointId)
        }
    }

    override fun start(listener: HostListener) {
        this.listener = listener
        try {
            client.startAdvertising(
                deviceName, SERVICE_ID, lifecycle,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { if (!isAlreadyRunning(it)) listener.onError("Nearby: ${it.message}") }
        } catch (e: Exception) {
            listener.onError("Nearby: ${e.message}")
        }
    }

    override fun send(text: String) {
        val to = synchronized(connected) { connected.toList() }
        if (to.isEmpty()) return
        client.sendPayload(to, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
    }

    override fun stop() {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        listener = null
    }
}

class NearbyClientLink(context: Context, private val deviceName: String) : ClientLink {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private var listener: ClientListener? = null
    @Volatile private var endpoint: String? = null

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener?.onMessage(String(bytes, Charsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onPairing(info.authenticationDigits)
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                endpoint = endpointId
                runCatching { client.stopDiscovery() }
                listener?.onConnected()
            } else {
                listener?.onError(LinkError.DENIED, "${result.status.statusMessage ?: result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpoint == endpointId) endpoint = null
            listener?.onDisconnected("lost")
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            listener?.onLost(endpointId)
        }
    }

    override fun start(listener: ClientListener) {
        this.listener = listener
        try {
            client.startDiscovery(
                SERVICE_ID, discovery,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnFailureListener { if (!isAlreadyRunning(it)) listener.onError(LinkError.OTHER, "Nearby: ${it.message}") }
        } catch (e: Exception) {
            listener.onError(LinkError.OTHER, "Nearby: ${e.message}")
        }
    }

    override fun connect(target: String, pin: String?) {
        try {
            client.requestConnection(deviceName, target, lifecycle)
                .addOnFailureListener { listener?.onError(LinkError.UNREACHABLE, it.message ?: "") }
        } catch (e: Exception) {
            listener?.onError(LinkError.OTHER, e.message ?: "")
        }
    }

    override fun send(text: String) {
        val to = endpoint ?: return
        client.sendPayload(to, Payload.fromBytes(text.toByteArray(Charsets.UTF_8)))
    }

    override fun stop() {
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        listener = null
        endpoint = null
    }
}

package com.gocavgo.ikuriye.service

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory

/**
 * Live progress of one waypoint as published by Navigation on
 * `car/{vehicleId}/trip/updates`. [waypointIndex] is the 0-based index into the
 * trip's original waypoint list, which the backend maps to the DB `order` =
 * index + 1 (origin excluded).
 */
data class MqttWaypointProgress(
    val waypointIndex: Int,
    val waypointName: String?,
    val state: String?, // APPROACHING | ARRIVED | DONE
    val remainingDistance: Double?,
    val remainingTime: Double?
)

/**
 * Parsed `car/{vehicleId}/trip/updates` payload (server NavigaTripDto).
 * [waypoints] is in travel order; the last element is the destination.
 */
data class MqttTripUpdate(
    val tripId: Long?,
    val carId: String?,
    val status: String?,
    val waypoints: List<MqttWaypointProgress>,
    val latitude: Double?,
    val longitude: Double?,
    val speed: Double?
)

/**
 * Subscribes to MQTT trip update channels for a specific vehicle.
 *
 * Topics subscribed:
 * - `car/{vehicleId}/trip` — trip assignment events (SCHEDULED, CANCELLED)
 * - `car/{vehicleId}/trip/updates` — real-time trip progress updates from Navigation
 *
 * These are published by cavgomqt (MqttService) when trips are created, updated,
 * or completed. This subscriber provides real-time awareness so the driver sees
 * new trips without polling.
 *
 * Polling in TripViewModel acts as a fallback if MQTT is unavailable.
 */
object MqttTripSubscriber {

    private const val TAG = "MqttTripSubscriber"

    private val brokerUrl: String get() = BuildConfig.MQTT_BROKER_URL
    private val brokerUsername: String get() = BuildConfig.MQTT_USERNAME
    private val brokerPassword: String get() = BuildConfig.MQTT_PASSWORD

    private var client: MqttClient? = null
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectFuture = AtomicReference<ScheduledFuture<*>?>(null)

    @Volatile private var isConnected = false
    @Volatile private var currentVehicleId: String? = null
    @Volatile private var onTripUpdate: ((MqttTripUpdate?) -> Unit)? = null

    private var reconnectAttempt = 0
    private const val MAX_RECONNECT_MS = 60_000L

    /**
     * Subscribe to trip channels for [vehicleId].
     * If already subscribed to a different vehicle, disconnects first.
     * [callback] is invoked on the MQTT executor thread when a trip message
     * arrives, with the parsed progress payload ([MqttTripUpdate]) — or null
     * when the payload is a plain lifecycle event with no waypoint progress.
     */
    fun subscribe(vehicleId: String, callback: (MqttTripUpdate?) -> Unit) {
        if (vehicleId == currentVehicleId && isConnected) {
            onTripUpdate = callback
            return
        }
        disconnect()
        currentVehicleId = vehicleId
        onTripUpdate = callback
        reconnectAttempt = 0
        connect()
    }

    fun disconnect() {
        reconnectFuture.get()?.cancel(false)
        currentVehicleId = null
        onTripUpdate = null
        synchronized(this) {
            try { client?.disconnect() } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
            client = null
            isConnected = false
            reconnectAttempt = 0
        }
    }

    private fun connect() {
        val vid = currentVehicleId ?: return
        if (brokerUrl.isBlank() || brokerUsername.isBlank()) {
            Log.w(TAG, "MQTT broker not configured — trip subscriber disabled")
            return
        }

        synchronized(this) {
            if (isConnected) return

            try {
                try { client?.close() } catch (_: Exception) {}

                val clientId = "ikuriye-trip-${vid}-${System.currentTimeMillis() % 100000}"
                client = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = false
                    userName = brokerUsername
                    password = brokerPassword.toCharArray()
                    mqttVersion = 4
                    if (brokerUrl.startsWith("ssl://")) {
                        socketFactory = SSLSocketFactory.getDefault()
                    }
                }

                client?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Trip subscriber connection lost: ${cause?.message}")
                        isConnected = false
                        scheduleReconnect()
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payloadStr = message?.payload?.let { String(it, Charsets.UTF_8) } ?: "(empty)"
                        Log.i(TAG, "🔔 [MQTT Trip Update Received] Topic: '$topic' | Payload: ${payloadStr.take(500)}")
                        onTripUpdate?.invoke(parseTripUpdate(payloadStr))
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client?.connect(options)
                isConnected = true
                reconnectAttempt = 0

                // Subscribe to trip channels
                val tripTopic = "car/$vid/trip"
                val tripUpdatesTopic = "car/$vid/trip/updates"
                client?.subscribe(tripTopic, 1)
                client?.subscribe(tripUpdatesTopic, 1)
                Log.i(TAG, "Subscribed to $tripTopic, $tripUpdatesTopic")
            } catch (e: Throwable) {
                Log.w(TAG, "Trip subscriber connect failed: ${e.message}")
                isConnected = false
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectFuture.get()?.cancel(false)
        val base = minOf(2000L * (1 shl reconnectAttempt), MAX_RECONNECT_MS)
        val jitter = (Math.random() * base / 4).toLong()
        val delay = base + jitter
        reconnectAttempt++
        reconnectFuture.set(executor.schedule({ connect() }, delay, TimeUnit.MILLISECONDS))
    }

    /**
     * Parses the server NavigaTripDto JSON into [MqttTripUpdate]. Lifecycle
     * events on `car/{vehicleId}/trip` have no waypointProgresses and produce a
     * result with an empty [MqttTripUpdate.waypoints] list.
     */
    private fun parseTripUpdate(payloadStr: String): MqttTripUpdate? {
        if (!payloadStr.startsWith("{") || payloadStr == "(empty)") return null
        return try {
            val json = JSONObject(payloadStr)
            val wpArr = json.optJSONArray("waypointProgresses")
            val waypoints = if (wpArr != null) {
                (0 until wpArr.length()).mapNotNull { i ->
                    val w = wpArr.optJSONObject(i) ?: return@mapNotNull null
                    MqttWaypointProgress(
                        waypointIndex = w.optInt("waypointIndex", -1),
                        waypointName = w.optString("waypointName", null).takeIf { it.isNotBlank() },
                        state = w.optString("state", null).takeIf { it.isNotBlank() },
                        remainingDistance = if (w.has("remainingDistance") && !w.isNull("remainingDistance")) w.optDouble("remainingDistance").takeIf { !it.isNaN() } else null,
                        remainingTime = if (w.has("remainingTime") && !w.isNull("remainingTime")) w.optDouble("remainingTime").takeIf { !it.isNaN() } else null
                    )
                }
            } else emptyList()
            val loc = json.optJSONObject("currentLocation")
            MqttTripUpdate(
                tripId = if (json.has("id") && !json.isNull("id")) json.optLong("id").takeIf { it > 0 } else null,
                carId = json.optString("carId", null).takeIf { it.isNotBlank() },
                status = json.optString("status", null).takeIf { it.isNotBlank() },
                waypoints = waypoints,
                latitude = loc?.let { if (it.has("latitude")) it.optDouble("latitude").takeIf { d -> !d.isNaN() } else null },
                longitude = loc?.let { if (it.has("longitude")) it.optDouble("longitude").takeIf { d -> !d.isNaN() } else null },
                speed = loc?.let { if (it.has("speed")) it.optDouble("speed").takeIf { d -> !d.isNaN() } else null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse trip update payload: ${e.message}")
            null
        }
    }
}

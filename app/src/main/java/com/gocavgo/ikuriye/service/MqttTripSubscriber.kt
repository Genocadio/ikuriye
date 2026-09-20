package com.gocavgo.ikuriye.service

import android.util.Log
import com.gocavgo.ikuriye.BuildConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory

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
    @Volatile private var onTripUpdate: (() -> Unit)? = null

    private var reconnectAttempt = 0
    private const val MAX_RECONNECT_MS = 60_000L

    /**
     * Subscribe to trip channels for [vehicleId].
     * If already subscribed to a different vehicle, disconnects first.
     * [callback] is invoked on the MQTT executor thread when a trip message arrives.
     */
    fun subscribe(vehicleId: String, callback: () -> Unit) {
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
                        Log.i(TAG, "🔔 [MQTT Trip Update Received] Topic: '$topic' | Payload: $payloadStr")
                        onTripUpdate?.invoke()
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
}

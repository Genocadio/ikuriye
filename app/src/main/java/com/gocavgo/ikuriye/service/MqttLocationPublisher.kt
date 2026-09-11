package com.gocavgo.ikuriye.service

import com.gocavgo.ikuriye.BuildConfig
import android.content.Context
import android.content.Intent
import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes GPS location to HiveMQ MQTT broker with:
 *
 * - **Batching**: GPS points accumulated in memory, flushed every [BATCH_INTERVAL_MS]
 *   when moving, or every [HEARTBEAT_INTERVAL_MS] when idle/background.
 * - **Offline queue**: If MQTT is disconnected, points go to SQLite [GpsPointQueue]
 *   and are flushed on reconnect.
 * - **Reconnection**: Exponential backoff with jitter, capped at [MAX_RECONNECT_MS].
 * - **Heartbeat mode**: When app is backgrounded >30 min with no active trip,
 *   publishes a heartbeat every 5 min instead of GPS.
 * - **Connection state**: Broadcasts [ACTION_MQTT_STATE_CHANGED] so ViewModel/UI
 *   can show reconnecting indicators.
 */
object MqttLocationPublisher {

    private const val TAG = "MqttLocation"

    // ── Broker config ──────────────────────────────────────────────────────
    // Supplied via secrets.properties / CI env at build time → BuildConfig.
    // See secrets.properties.example (keys MQTT_BROKER_URL / MQTT_USERNAME / MQTT_PASSWORD).
    private val brokerUrl: String get() = BuildConfig.MQTT_BROKER_URL
    private val brokerUsername: String get() = BuildConfig.MQTT_USERNAME
    private val brokerPassword: String get() = BuildConfig.MQTT_PASSWORD

    // ── Batching ───────────────────────────────────────────────────────────
    private const val BATCH_INTERVAL_MS = 10_000L     // flush every 10s when moving
    private const val HEARTBEAT_INTERVAL_MS = 300_000L // heartbeat every 5 min
    private const val BATCH_SIZE = 20                   // max points per Protobuf batch
    private const val MAX_RECONNECT_MS = 128_000L

    // ── Public broadcast ───────────────────────────────────────────────────
    const val ACTION_MQTT_STATE_CHANGED = "com.gocavgo.ikuriye.MQTT_STATE_CHANGED"
    const val EXTRA_MQTT_CONNECTED = "mqtt_connected"
    const val EXTRA_MQTT_QUEUE_SIZE = "mqtt_queue_size"

    // ── State ──────────────────────────────────────────────────────────────
    private var client: MqttClient? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reconnectFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val batchFuture = AtomicReference<ScheduledFuture<*>?>(null)
    private val lock = Any()

    @Volatile private var isConnected = false
    @Volatile private var isShuttingDown = false
    @Volatile private var reconnectAttempt = 0

    @Volatile var vehicleId: String? = null
    @Volatile var userId: String? = null

    /** In-memory batch buffer — flushed to MQTT or SQLite on interval. */
    private val batchBuffer = mutableListOf<GpsPointQueue.GpsPoint>()
    private val batchLock = Any()

    private var appContext: Context? = null
    private var gpsQueue: GpsPointQueue? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        gpsQueue = GpsPointQueue(context.applicationContext)
        startBatchFlusher()
        Log.i(TAG, "Initialized — queue size: ${gpsQueue?.size()}")
    }

    fun disconnect() {
        isShuttingDown = true
        reconnectFuture.get()?.cancel(false)
        batchFuture.get()?.cancel(false)
        synchronized(lock) {
            try { client?.disconnect() } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
            client = null
            isConnected = false
            reconnectAttempt = 0
        }
        // Flush remaining batch to queue
        flushBatchToQueue()
        vehicleId = null
        userId = null
        isShuttingDown = false
        broadcastState()
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun publishLocation(lat: Double, lng: Double, speed: Float, bearing: Float?, accuracy: Float?) {
        if (userId.isNullOrBlank()) return

        val point = GpsPointQueue.GpsPoint(
            id = 0, lat = lat, lng = lng, speed = speed,
            bearing = bearing, accuracy = accuracy,
            timestamp = System.currentTimeMillis()
        )

        // Add to in-memory batch buffer
        synchronized(batchLock) {
            batchBuffer.add(point)
        }

        // If buffer is large enough, flush immediately
        if (synchronized(batchLock) { batchBuffer.size >= BATCH_SIZE }) {
            executor.execute { flushBatch() }
        }
    }

    fun setHeartbeatMode(enabled: Boolean) {
        // When entering heartbeat mode, flush current batch
        if (enabled) {
            executor.execute { flushBatch() }
        }
    }

    // ── Batch flushing ─────────────────────────────────────────────────────

    private fun startBatchFlusher() {
        batchFuture.get()?.cancel(false)
        val future = executor.scheduleWithFixedDelay(
            { flushBatch() },
            BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
        batchFuture.set(future)
    }

    private fun flushBatch() {
        val points: List<GpsPointQueue.GpsPoint>
        synchronized(batchLock) {
            if (batchBuffer.isEmpty()) return
            points = batchBuffer.toList()
            batchBuffer.clear()
        }

        if (isConnected) {
            publishBatchToMqtt(points)
        } else {
            // MQTT offline — persist to SQLite for later
            for (p in points) {
                gpsQueue?.enqueue(p.lat, p.lng, p.speed, p.bearing, p.accuracy, p.timestamp)
            }
            Log.d(TAG, "MQTT offline — queued ${points.size} points (${gpsQueue?.size()} total)")
        }

        // Also drain any previously queued points
        if (isConnected) {
            drainOfflineQueue()
        }
    }

    private fun flushBatchToQueue() {
        synchronized(batchLock) {
            for (p in batchBuffer) {
                gpsQueue?.enqueue(p.lat, p.lng, p.speed, p.bearing, p.accuracy, p.timestamp)
            }
            batchBuffer.clear()
        }
    }

    // ── MQTT publish ───────────────────────────────────────────────────────

    private fun publishBatchToMqtt(points: List<GpsPointQueue.GpsPoint>) {
        try {
            // 1. Vehicle topic (Protobuf) — if driver has a car
            val vid = vehicleId
            if (!vid.isNullOrBlank()) {
                val encodedPoints = points.map { p ->
                    encodeLocationPoint(p.lat, p.lng, p.speed, p.bearing, p.accuracy, p.timestamp)
                }
                val batch = encodeLocationBatch(vid, "", encodedPoints)
                val msg = MqttMessage(batch).apply { qos = 1; isRetained = false }
                client?.publish("vehicles/$vid/location/batch", msg)
                Log.d(TAG, "Published ${points.size} points to vehicles/$vid/location/batch (${batch.size} bytes)")
            }

            // 2. User topic (JSON) — always
            for (p in points) {
                val json = buildString {
                    append("{")
                    append("\"userId\":\"$userId\",")
                    if (!vid.isNullOrBlank()) append("\"vehicleId\":\"$vid\",")
                    append("\"latitude\":${p.lat},\"longitude\":${p.lng},")
                    append("\"speed\":${p.speed.toDouble()},")
                    if (p.bearing != null) append("\"bearing\":${p.bearing.toDouble()},")
                    if (p.accuracy != null) append("\"accuracy\":${p.accuracy.toDouble()},")
                    append("\"timestamp\":${p.timestamp}")
                    append("}")
                }
                val msg = MqttMessage(json.toByteArray(Charsets.UTF_8)).apply { qos = 1; isRetained = false }
                client?.publish("users/$userId/location/update", msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Publish failed: ${e.message}")
            isConnected = false
            // Persist failed points back to queue
            for (p in points) {
                gpsQueue?.enqueue(p.lat, p.lng, p.speed, p.bearing, p.accuracy, p.timestamp)
            }
            scheduleReconnect()
        }
    }

    private fun drainOfflineQueue() {
        val queue = gpsQueue ?: return
        while (true) {
            val points = queue.drain(BATCH_SIZE)
            if (points.isEmpty()) break
            try {
                publishBatchToMqtt(points)
                queue.acknowledge(points.map { it.id })
                Log.d(TAG, "Drained ${points.size} offline points (${queue.size()} remaining)")
            } catch (e: Exception) {
                Log.w(TAG, "Drain failed mid-batch: ${e.message}")
                break
            }
        }
    }

    // ── Reconnection with exponential backoff + jitter ─────────────────────

    private fun scheduleReconnect() {
        if (isShuttingDown) return
        reconnectFuture.get()?.cancel(false)

        val base = minOf(1000L * (1 shl reconnectAttempt), MAX_RECONNECT_MS)
        val jitter = Random().nextLong(base / 4) // 0..25% jitter
        val delay = base + jitter
        reconnectAttempt++

        Log.i(TAG, "Scheduling reconnect #${reconnectAttempt} in ${delay}ms")
        val future = executor.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        reconnectFuture.set(future)
    }

    private fun connect() {
        if (isShuttingDown || (isConnected && client?.isConnected == true)) return

        // Broker must be configured in secrets.properties before any of this can work.
        if (brokerUrl.isBlank() || brokerUsername.isBlank() || brokerPassword.isBlank()) {
            Log.e(TAG, "MQTT broker not configured — set MQTT_BROKER_URL / MQTT_USERNAME / MQTT_PASSWORD in secrets.properties and rebuild.")
            return
        }

        synchronized(lock) {
            if (isShuttingDown || (isConnected && client?.isConnected == true)) return

            try { client?.close() } catch (_: Exception) {}

            val clientId = "ikuriye-${userId ?: System.currentTimeMillis()}"
            client = MqttClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
                isAutomaticReconnect = false // We handle reconnect ourselves
                maxInflight = 100
                userName = brokerUsername
                password = brokerPassword.toCharArray()
                mqttVersion = 4

                if (brokerUrl.startsWith("ssl://")) {
                    socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
                }
            }

            client?.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    isConnected = false
                    broadcastState()
                    if (!isShuttingDown) scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })

            client?.connect(options)
            isConnected = true
            reconnectAttempt = 0 // Reset on successful connect
            broadcastState()
            Log.i(TAG, "Connected to MQTT as $clientId")

            // Immediately drain any queued offline points
            executor.execute { drainOfflineQueue() }
        }
    }

    // ── Connection state broadcast ─────────────────────────────────────────

    private fun broadcastState() {
        val ctx = appContext ?: return
        val intent = Intent(ACTION_MQTT_STATE_CHANGED).apply {
            putExtra(EXTRA_MQTT_CONNECTED, isConnected)
            putExtra(EXTRA_MQTT_QUEUE_SIZE, gpsQueue?.size() ?: 0)
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(intent)
    }

    // ── Protobuf encoding (manual, no protoc) ─────────────────────────────

    private fun encodeLocationPoint(
        lat: Double, lng: Double, speed: Float,
        bearing: Float?, accuracy: Float?, timestamp: Long
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.write(0x09); buf.write(doubleToBytes(lat))       // field 1: double lat
        buf.write(0x11); buf.write(doubleToBytes(lng))       // field 2: double lng
        buf.write(0x1D); buf.write(floatToBytes(speed))      // field 3: float speed
        if (bearing != null) { buf.write(0x25); buf.write(floatToBytes(bearing)) }   // field 4
        if (accuracy != null) { buf.write(0x2D); buf.write(floatToBytes(accuracy)) } // field 5
        buf.write(0x30); buf.write(encodeVarint(timestamp))  // field 6: int64 timestamp
        return buf.toByteArray()
    }

    private fun encodeLocationBatch(vehicleId: String, plate: String, points: List<ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        if (vehicleId.isNotEmpty()) {
            buf.write(0x0A)
            val bytes = vehicleId.toByteArray(Charsets.UTF_8)
            buf.write(encodeVarint(bytes.size.toLong())); buf.write(bytes)
        }
        if (plate.isNotEmpty()) {
            buf.write(0x12)
            val bytes = plate.toByteArray(Charsets.UTF_8)
            buf.write(encodeVarint(bytes.size.toLong())); buf.write(bytes)
        }
        for (point in points) {
            buf.write(0x1A)
            buf.write(encodeVarint(point.size.toLong())); buf.write(point)
        }
        return buf.toByteArray()
    }

    private fun encodeVarint(value: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        var v = value
        while (v > 0x7F) { buf.write((v.toInt() and 0x7F) or 0x80); v = v ushr 7 }
        buf.write(v.toInt() and 0x7F)
        return buf.toByteArray()
    }

    private fun doubleToBytes(value: Double): ByteArray = ByteBuffer.allocate(8).putDouble(value).array()
    private fun floatToBytes(value: Float): ByteArray = ByteBuffer.allocate(4).putFloat(value).array()
}

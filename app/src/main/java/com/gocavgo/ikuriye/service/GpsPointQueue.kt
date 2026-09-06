package com.gocavgo.ikuriye.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Persistent SQLite queue for GPS points that couldn't be published via MQTT.
 *
 * Points are inserted on every GPS fix when MQTT is disconnected or publish fails.
 * They are drained in FIFO order once MQTT reconnects. Old entries (>2 hours) are
 * purged on every open to prevent unbounded growth.
 *
 * Thread-safe: all public methods synchronize on [db].
 */
class GpsPointQueue(context: Context) {

    companion object {
        private const val TAG = "GpsPointQueue"
        private const val DB_NAME = "gps_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE = "gps_points"
        private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val MAX_ROWS = 500 // hard cap
    }

    private val db: SQLiteDatabase = GpsDbHelper(context).writableDatabase

    init {
        purgeOld()
    }

    /** Insert a GPS point into the queue. Returns true on success. */
    fun enqueue(
        lat: Double, lng: Double, speed: Float,
        bearing: Float?, accuracy: Float?,
        timestamp: Long
    ): Boolean {
        return try {
            synchronized(db) {
                // Enforce hard cap — drop oldest if exceeded
                val curCount = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
                val total = if (curCount.moveToFirst()) curCount.getInt(0) else 0
                curCount.close()
                if (total >= MAX_ROWS) {
                    db.delete(TABLE, "id IN (SELECT id FROM $TABLE ORDER BY timestamp ASC LIMIT ${total - MAX_ROWS + 1})", null)
                }

                val cv = ContentValues().apply {
                    put("lat", lat)
                    put("lng", lng)
                    put("speed", speed)
                    put("bearing", bearing)
                    put("accuracy", accuracy)
                    put("timestamp", timestamp)
                    put("created_at", System.currentTimeMillis())
                }
                val rowId = db.insert(TABLE, null, cv)
                rowId > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enqueue failed: ${e.message}")
            false
        }
    }

    /**
     * Drain up to [batchSize] points from the queue in FIFO order.
     * Returns the list and a function to call after successful publish.
     */
    fun drain(batchSize: Int = 100): List<GpsPoint> {
        synchronized(db) {
            val points = mutableListOf<GpsPoint>()
            val cursor = db.rawQuery(
                "SELECT id, lat, lng, speed, bearing, accuracy, timestamp FROM $TABLE ORDER BY timestamp ASC LIMIT $batchSize",
                null
            )
            while (cursor.moveToNext()) {
                points.add(
                    GpsPoint(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        lat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat")),
                        lng = cursor.getDouble(cursor.getColumnIndexOrThrow("lng")),
                        speed = cursor.getFloat(cursor.getColumnIndexOrThrow("speed")),
                        bearing = if (cursor.isNull(cursor.getColumnIndexOrThrow("bearing"))) null else cursor.getFloat(cursor.getColumnIndexOrThrow("bearing")),
                        accuracy = if (cursor.isNull(cursor.getColumnIndexOrThrow("accuracy"))) null else cursor.getFloat(cursor.getColumnIndexOrThrow("accuracy")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
            cursor.close()
            return points
        }
    }

    /** Remove specific points from the queue after successful publish. */
    fun acknowledge(pointIds: List<Long>) {
        if (pointIds.isEmpty()) return
        synchronized(db) {
            val placeholders = pointIds.joinToString(",") { "?" }
            db.delete(TABLE, "id IN ($placeholders)", pointIds.map { it.toString() }.toTypedArray())
        }
    }

    /** Number of queued points. */
    fun size(): Int {
        synchronized(db) {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            return count
        }
    }

    /** Remove points older than [MAX_AGE_MS] and enforce hard cap. */
    fun purgeOld() {
        synchronized(db) {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            val deleted = db.delete(TABLE, "created_at < ?", arrayOf(cutoff.toString()))
            if (deleted > 0) Log.d(TAG, "Purged $deleted old GPS points")
        }
    }

    /** Clear all queued points. */
    fun clear() {
        synchronized(db) {
            db.delete(TABLE, null, null)
        }
    }

    fun close() {
        synchronized(db) {
            try { db.close() } catch (_: Exception) {}
        }
    }

    data class GpsPoint(
        val id: Long,
        val lat: Double,
        val lng: Double,
        val speed: Float,
        val bearing: Float?,
        val accuracy: Float?,
        val timestamp: Long
    )

    private class GpsDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    speed REAL NOT NULL,
                    bearing REAL,
                    accuracy REAL,
                    timestamp INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_gps_timestamp ON $TABLE (timestamp ASC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}

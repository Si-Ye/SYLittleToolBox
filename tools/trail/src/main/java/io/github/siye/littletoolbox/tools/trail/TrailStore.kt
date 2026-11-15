package io.github.siye.littletoolbox.tools.trail

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class TrailFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val time: Long,
    val elapsedMs: Long = 0L,
)

internal data class TrailPoint(val fix: TrailFix, val breakBefore: Boolean)

internal data class TrailRoute(
    val startedAt: Long,
    val endedAt: Long?,
    val points: List<TrailPoint>,
)

internal data class ParkingSpot(
    val markedAt: Long,
    val fix: TrailFix? = null,
    val floor: String = "",
    val space: String = "",
    val photo: String? = null,
)

internal data class TrailSnapshot(val route: TrailRoute?, val parking: ParkingSpot?)

internal class TrailStore private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "trail.db", null, 1) {
    val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE route (id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER)")
        db.execSQL(
            """CREATE TABLE point (
                id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL NOT NULL,
                fix_at INTEGER NOT NULL, break_before INTEGER NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE parking (
                id INTEGER PRIMARY KEY, marked_at INTEGER NOT NULL, lat REAL, lon REAL, accuracy REAL,
                fix_at INTEGER, floor TEXT NOT NULL, space TEXT NOT NULL, photo TEXT)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun read(): TrailSnapshot = withContext(Dispatchers.IO) {
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val route = db.rawQuery("SELECT started_at, ended_at FROM route WHERE id = 1", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val points = db.rawQuery(
                        "SELECT lat, lon, accuracy, fix_at, break_before FROM point ORDER BY id",
                        null,
                    ).use { rows ->
                        buildList {
                            while (rows.moveToNext()) {
                                add(TrailPoint(rows.readFix(), rows.getInt(4) == 1))
                            }
                        }
                    }
                    TrailRoute(cursor.getLong(0), if (cursor.isNull(1)) null else cursor.getLong(1), points)
                } else null
            }
            TrailSnapshot(route, readParking(db)).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    suspend fun beginRoute(startedAt: Long) = change { db ->
        db.delete("point", null, null)
        db.delete("route", null, null)
        db.insertOrThrow("route", null, ContentValues().apply {
            put("id", 1)
            put("started_at", startedAt)
        })
    }

    suspend fun append(points: List<TrailPoint>, endedAt: Long? = null) = change { db ->
        points.forEach { point ->
            db.insertOrThrow("point", null, ContentValues().apply {
                putFix(point.fix)
                put("break_before", point.breakBefore)
            })
        }
        if (endedAt != null) {
            db.update("route", ContentValues().apply { put("ended_at", endedAt) }, "id = 1", null)
        }
    }

    suspend fun clearRoute() = change { db ->
        db.delete("point", null, null)
        db.delete("route", null, null)
    }

    suspend fun saveParking(spot: ParkingSpot, selectedPhoto: Uri?) = withContext(Dispatchers.IO) {
        val newPhoto = selectedPhoto?.let(::copyPhoto)
        val previousPhoto = try {
            change { db ->
                val previous = readParking(db)?.photo
                db.replaceOrThrow("parking", null, ContentValues().apply {
                    put("id", 1)
                    put("marked_at", spot.markedAt)
                    spot.fix?.let { putFix(it) }
                    put("floor", spot.floor)
                    put("space", spot.space)
                    put("photo", newPhoto ?: spot.photo)
                })
                previous
            }
        } catch (error: Exception) {
            newPhoto?.let { photoFile(it).delete() }
            throw error
        }
        previousPhoto?.takeIf { it != (newPhoto ?: spot.photo) }?.let { photoFile(it).delete() }
    }

    suspend fun clearParking() = withContext(Dispatchers.IO) {
        val photo = change { db ->
            val previous = readParking(db)?.photo
            db.delete("parking", "id = 1", null)
            previous
        }
        photo?.let { photoFile(it).delete() }
    }

    fun photoFile(name: String) = File(context.filesDir, name)

    private fun copyPhoto(uri: Uri): String {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val ratio = minOf(1.0, 1_600.0 / maxOf(width, height))
            decoder.setTargetSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val file = photoFile("trail-parking-${UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw IOException("Parking photo encode failed")
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
        return file.name
    }

    private suspend fun <T> change(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        val result = try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        changes.update { it + 1 }
        result
    }

    private fun readParking(db: SQLiteDatabase): ParkingSpot? = db.rawQuery(
        "SELECT lat, lon, accuracy, fix_at, marked_at, floor, space, photo FROM parking WHERE id = 1",
        null,
    ).use { row ->
        if (!row.moveToFirst()) return@use null
        ParkingSpot(row.getLong(4), if (row.isNull(0)) null else row.readFix(), row.getString(5), row.getString(6), row.getString(7))
    }

    private fun Cursor.readFix() = TrailFix(getDouble(0), getDouble(1), getFloat(2), getLong(3))

    private fun ContentValues.putFix(fix: TrailFix) {
        put("lat", fix.latitude)
        put("lon", fix.longitude)
        put("accuracy", fix.accuracy)
        put("fix_at", fix.time)
    }

    companion object {
        @Volatile private var instance: TrailStore? = null

        fun get(context: Context): TrailStore = instance ?: synchronized(this) {
            instance ?: TrailStore(context.applicationContext).also { instance = it }
        }
    }
}

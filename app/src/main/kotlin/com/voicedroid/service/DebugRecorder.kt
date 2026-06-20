package com.voicedroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.voicedroid.accessibility.AxOps
import com.voicedroid.service.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records the last N taps and screenshots to app-external storage so they can be
 * pulled off the device with adb for offline diagnosis.
 *
 *   adb pull /sdcard/Android/data/com.voicedroid/files/debug/
 *
 * Each record is a triple of files sharing a prefix:
 *   <id>.jpg            — screenshot. Tap records overlay a crosshair at the tap point.
 *   <id>.json           — metadata (dimensions, coords, timestamp, active package, etc.)
 *   <id>.tree.txt       — accessibility tree dump (tap_text only — to debug missing matches)
 *
 * A copy of the most recent screenshot is also written as `latest.jpg` for quick pulls.
 */
class DebugRecorder(context: Context) {

    private val dir: File = File(context.getExternalFilesDir(null), "debug").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicInteger(0)
    private val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val jsonFmt = Json { prettyPrint = true }

    init {
        Log.i(TAG, "debug records dir: $dir")
    }

    // ---- public API ------------------------------------------------------

    /** Record a screenshot tool call. Saves the exact bytes we sent to the model. */
    fun recordScreenshot(jpeg: ByteArray, width: Int, height: Int) {
        val id = nextId("screenshot")
        val tx = Transcripts.snapshot()
        scope.launch {
            saveJpeg(id, jpeg)
            saveMetadata(id, buildJsonObject {
                put("id", id)
                put("type", "screenshot")
                put("timestamp", currentTimeString())
                put("width", width)
                put("height", height)
                put("active_package", AxOps.activePackage() ?: "")
                put("user_said", tx.lastHeard)
                put("model_said", tx.modelResponse)
                put("notes", "image bytes are exactly what was sent to the model")
            })
            prune()
        }
    }

    /** Record a tap_xy action. Captures a fresh screenshot and overlays a crosshair at (x,y). */
    suspend fun recordTapXY(x: Float, y: Float, success: Boolean) {
        val id = nextId("tap_xy")
        val tx = Transcripts.snapshot()
        val shot = runCatching { AxOps.takeScreenshot() }.getOrNull()
        scope.launch {
            val (w, h) = if (shot != null) {
                val annotated = overlayTap(shot.jpeg, x, y)
                saveJpeg(id, annotated)
                shot.width to shot.height
            } else 0 to 0
            saveMetadata(id, buildJsonObject {
                put("id", id)
                put("type", "tap_xy")
                put("timestamp", currentTimeString())
                put("tap_x", x)
                put("tap_y", y)
                put("screen_width", w)
                put("screen_height", h)
                put("success", success)
                put("active_package", AxOps.activePackage() ?: "")
                put("user_said", tx.lastHeard)
                put("model_said", tx.modelResponse)
            })
            prune()
        }
    }

    /**
     * Record a tap_near_text action. Saves an overlay that draws the anchor's bounding
     * box (yellow) plus the final tap crosshair (red), so the offset is obvious.
     */
    suspend fun recordTapNearText(
        anchor: String,
        anchorBounds: Rect,
        dx: Float,
        dy: Float,
        tapX: Float,
        tapY: Float,
        success: Boolean,
    ) {
        val id = nextId("tap_near_text")
        val tx = Transcripts.snapshot()
        val shot = runCatching { AxOps.takeScreenshot() }.getOrNull()
        scope.launch {
            val (w, h) = if (shot != null) {
                val annotated = overlayAnchorAndTap(shot.jpeg, anchorBounds, tapX, tapY)
                saveJpeg(id, annotated)
                shot.width to shot.height
            } else 0 to 0
            saveMetadata(id, buildJsonObject {
                put("id", id)
                put("type", "tap_near_text")
                put("timestamp", currentTimeString())
                put("anchor_text", anchor)
                put("anchor_bounds", "${anchorBounds.left},${anchorBounds.top},${anchorBounds.right},${anchorBounds.bottom}")
                put("dx", dx)
                put("dy", dy)
                put("tap_x", tapX)
                put("tap_y", tapY)
                put("screen_width", w)
                put("screen_height", h)
                put("success", success)
                put("active_package", AxOps.activePackage() ?: "")
                put("user_said", tx.lastHeard)
                put("model_said", tx.modelResponse)
            })
            prune()
        }
    }

    /**
     * Record a tap_text action. Takes a fresh screenshot, overlays a crosshair where the
     * matched node was (if any), and saves the AX tree as a sidecar text file for
     * debugging missing matches.
     */
    suspend fun recordTapText(
        query: String,
        matchedCenterX: Float?,
        matchedCenterY: Float?,
        success: Boolean,
        treeDump: String?,
    ) {
        val id = nextId("tap_text")
        val tx = Transcripts.snapshot()
        val shot = runCatching { AxOps.takeScreenshot() }.getOrNull()
        scope.launch {
            val (w, h) = if (shot != null) {
                val annotated = if (matchedCenterX != null && matchedCenterY != null) {
                    overlayTap(shot.jpeg, matchedCenterX, matchedCenterY)
                } else shot.jpeg
                saveJpeg(id, annotated)
                shot.width to shot.height
            } else 0 to 0
            saveMetadata(id, buildJsonObject {
                put("id", id)
                put("type", "tap_text")
                put("timestamp", currentTimeString())
                put("query", query)
                if (matchedCenterX != null && matchedCenterY != null) {
                    put("tap_x", matchedCenterX)
                    put("tap_y", matchedCenterY)
                }
                put("screen_width", w)
                put("screen_height", h)
                put("success", success)
                put("active_package", AxOps.activePackage() ?: "")
                put("user_said", tx.lastHeard)
                put("model_said", tx.modelResponse)
            })
            treeDump?.let { saveText(id, "tree", it) }
            prune()
        }
    }

    // ---- internals -------------------------------------------------------

    private fun overlayAnchorAndTap(jpeg: ByteArray, anchor: Rect, tapX: Float, tapY: Float): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        src.recycle()
        val canvas = Canvas(mutable)
        // yellow box around anchor text
        val box = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        canvas.drawRect(
            anchor.left.toFloat(), anchor.top.toFloat(),
            anchor.right.toFloat(), anchor.bottom.toFloat(),
            box,
        )
        // line from anchor center to tap point
        val line = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawLine(
            anchor.exactCenterX(), anchor.exactCenterY(),
            tapX, tapY, line,
        )
        // red crosshair at the actual tap point
        val red = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        val white = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }
        canvas.drawCircle(tapX, tapY, 60f, white)
        canvas.drawCircle(tapX, tapY, 60f, red)
        canvas.drawLine(tapX - 90, tapY, tapX + 90, tapY, red)
        canvas.drawLine(tapX, tapY - 90, tapX, tapY + 90, red)
        canvas.drawCircle(tapX, tapY, 10f, Paint().apply { color = Color.RED; style = Paint.Style.FILL })
        val out = ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, 85, out)
        mutable.recycle()
        return out.toByteArray()
    }

    private fun overlayTap(jpeg: ByteArray, x: Float, y: Float): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
        src.recycle()
        val canvas = Canvas(mutable)
        // white outline for contrast against red
        val outline = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }
        val red = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        // outer ring + crosshair + center dot
        canvas.drawCircle(x, y, 60f, outline)
        canvas.drawCircle(x, y, 60f, red)
        canvas.drawLine(x - 90, y, x + 90, y, outline)
        canvas.drawLine(x, y - 90, x, y + 90, outline)
        canvas.drawLine(x - 90, y, x + 90, y, red)
        canvas.drawLine(x, y - 90, x, y + 90, red)
        canvas.drawCircle(x, y, 10f, Paint().apply { color = Color.RED; style = Paint.Style.FILL })
        val out = ByteArrayOutputStream()
        mutable.compress(Bitmap.CompressFormat.JPEG, 75, out)
        mutable.recycle()
        return out.toByteArray()
    }

    private fun nextId(type: String): String {
        val ts = timestamp.format(Date())
        val n = seq.incrementAndGet()
        return "${ts}_${n}_$type"
    }

    private fun saveJpeg(id: String, bytes: ByteArray) {
        try {
            File(dir, "$id.jpg").writeBytes(bytes)
            // Convenience: also copy to a fixed name so we can adb pull the most recent
            // without listing the directory first.
            File(dir, "latest.jpg").writeBytes(bytes)
        } catch (e: Throwable) {
            Log.w(TAG, "saveJpeg failed: ${e.message}")
        }
    }

    private fun saveMetadata(id: String, json: JsonObject) {
        try {
            File(dir, "$id.json").writeText(
                jsonFmt.encodeToString(JsonObject.serializer(), json)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "saveMetadata failed: ${e.message}")
        }
    }

    private fun saveText(id: String, suffix: String, text: String) {
        try {
            File(dir, "$id.$suffix.txt").writeText(text)
        } catch (e: Throwable) {
            Log.w(TAG, "saveText failed: ${e.message}")
        }
    }

    /** Keep only the most recent [MAX_RECORDS] record groups. Files are pruned per id prefix. */
    private fun prune() {
        try {
            val files = dir.listFiles() ?: return
            val ids = files
                .map { it.name.substringBefore('.') }
                .filter { it.matches(Regex("\\d{8}-\\d{6}-\\d{3}_\\d+_.*")) }
                .toSortedSet()
            if (ids.size <= MAX_RECORDS) return
            val toDelete = ids.toList().dropLast(MAX_RECORDS)
            for (oldId in toDelete) {
                files.filter { it.name.startsWith(oldId) }.forEach { it.delete() }
            }
            Log.d(TAG, "pruned ${toDelete.size} record(s)")
        } catch (e: Throwable) {
            Log.w(TAG, "prune failed: ${e.message}")
        }
    }

    private fun currentTimeString(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return iso.format(Date())
    }

    companion object {
        private const val TAG = "DebugRecorder"
        private const val MAX_RECORDS = 10
    }
}

package com.voicedroid.tools

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.voicedroid.accessibility.AxOps
import com.voicedroid.service.DebugRecorder
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed class ToolResult {
    data class Text(val output: String) : ToolResult()
    data class TextWithImage(val output: String, val jpeg: ByteArray) : ToolResult()
}

/** Routes model tool calls to [AxOps] + [Launcher]. */
class ToolDispatcher(private val ctx: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val debug = DebugRecorder(ctx)

    suspend fun dispatch(name: String, argsJson: String): ToolResult {
        return try {
            val args = parseArgs(argsJson)
            when (name) {
                "launch_app" -> text(launchApp(args))
                "close_app" -> text(closeApp(args))
                "tap_text" -> text(tapText(args))
                "tap_near_text" -> text(tapNearText(args))
                "tap_xy" -> text(tapXY(args))
                "scroll" -> text(scroll(args))
                "swipe" -> text(swipe(args))
                "type" -> text(type(args))
                "key" -> text(key(args))
                "read_screen" -> text(readScreen())
                "dump_tree" -> text(dumpTree(args))
                "screenshot" -> screenshot()
                "active_package" -> text(activePackage())
                "wait_ms" -> text(waitMs(args))
                else -> text(err("unknown tool: $name"))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "tool $name failed", e)
            text(err(e.message ?: e.toString()))
        }
    }

    private fun text(s: String) = ToolResult.Text(s)

    private fun parseArgs(s: String): Map<String, JsonElement> {
        if (s.isBlank()) return emptyMap()
        val obj = json.parseToJsonElement(s).jsonObject
        return obj.toMap()
    }

    private fun launchApp(args: Map<String, JsonElement>): String {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return err("missing 'name'")
        val r = Launcher.launch(ctx, name)
        return if (r.ok) ok(buildJsonObject {
            put("launched", r.label ?: name)
            r.pkg?.let { put("package", it) }
        })
        else err(r.error ?: "launch failed")
    }

    private fun closeApp(args: Map<String, JsonElement>): String {
        val name = args["name"]?.jsonPrimitive?.content
            ?: return err("missing 'name'")
        val r = Launcher.close(ctx, name)
        return if (r.ok) ok(buildJsonObject {
            put("closed", r.label ?: name)
            r.pkg?.let { put("package", it) }
        })
        else err(r.error ?: "close failed")
    }

    private suspend fun tapText(args: Map<String, JsonElement>): String {
        val text = args["text"]?.jsonPrimitive?.content
            ?: return err("missing 'text'")
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        // Resolve the node ourselves so the debug recorder can mark the matched bounds.
        val node = AxOps.findByText(text)
        var cx: Float? = null
        var cy: Float? = null
        if (node != null) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (!r.isEmpty) { cx = r.exactCenterX(); cy = r.exactCenterY() }
        }
        val didTap = node?.let { AxOps.click(it) } ?: false
        debug.recordTapText(text, cx, cy, didTap, AxOps.dumpTree(maxDepth = 25))
        return if (didTap) ok() else err("no element matched '$text'")
    }

    private suspend fun tapXY(args: Map<String, JsonElement>): String {
        val x = args["x"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'x'")
        val y = args["y"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'y'")
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        val ok = AxOps.tapXY(x, y)
        debug.recordTapXY(x, y, ok)
        return if (ok) ok() else err("tap failed")
    }

    private suspend fun tapNearText(args: Map<String, JsonElement>): String {
        val anchor = args["anchor_text"]?.jsonPrimitive?.content
            ?: return err("missing 'anchor_text'")
        val dx = args["dx"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'dx'")
        val dy = args["dy"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'dy'")
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        val node = AxOps.findByText(anchor)
            ?: return err("no on-screen text matches '$anchor' — call read_screen or screenshot to confirm what's visible")
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return err("anchor '$anchor' has no bounds")
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val tx = cx + dx
        val ty = cy + dy
        val ok = AxOps.tapXY(tx, ty)
        debug.recordTapNearText(anchor, r, dx, dy, tx, ty, ok)
        return if (ok) ok(buildJsonObject {
            put("anchor_bounds", "${r.left},${r.top},${r.right},${r.bottom}")
            put("tap_x", tx)
            put("tap_y", ty)
        }) else err("tap_near_text gesture failed")
    }

    private suspend fun scroll(args: Map<String, JsonElement>): String {
        val dir = args["direction"]?.jsonPrimitive?.content
            ?: return err("missing 'direction'")
        val distance = args["distance"]?.jsonPrimitive?.floatOrNull ?: 0.6f
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        return if (AxOps.scroll(dir, distance)) ok() else err("scroll failed")
    }

    private suspend fun swipe(args: Map<String, JsonElement>): String {
        val x1 = args["x1"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'x1'")
        val y1 = args["y1"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'y1'")
        val x2 = args["x2"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'x2'")
        val y2 = args["y2"]?.jsonPrimitive?.floatOrNull ?: return err("missing 'y2'")
        val dur = (args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 250).toLong()
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        return if (AxOps.swipe(x1, y1, x2, y2, dur)) ok() else err("swipe failed")
    }

    private fun type(args: Map<String, JsonElement>): String {
        val text = args["text"]?.jsonPrimitive?.content ?: return err("missing 'text'")
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        return if (AxOps.typeText(text)) ok() else err("no focused editable field")
    }

    private fun key(args: Map<String, JsonElement>): String {
        val name = args["name"]?.jsonPrimitive?.content ?: return err("missing 'name'")
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        val didIt = when (name.lowercase()) {
            "back" -> AxOps.back()
            "home" -> AxOps.home()
            "recents" -> AxOps.recents()
            "notifications" -> AxOps.notifications()
            else -> return err("unknown key '$name'")
        }
        return if (didIt) ok() else err("$name failed")
    }

    private fun readScreen(): String {
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        val text = AxOps.readScreen()
        return ok(buildJsonObject { put("text", text) })
    }

    private fun dumpTree(args: Map<String, JsonElement>): String {
        if (!AxOps.isReady()) return err("accessibility service not enabled")
        val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 15
        val tree = AxOps.dumpTree(maxDepth.coerceIn(1, 30))
        return ok(buildJsonObject { put("tree", tree) })
    }

    private suspend fun screenshot(): ToolResult {
        if (!AxOps.isReady()) return text(err("accessibility service not enabled"))
        val shot = AxOps.takeScreenshot()
            ?: return text(err("screenshot failed (API 30+ required)"))
        debug.recordScreenshot(shot.jpeg, shot.width, shot.height)
        // Hand the dimensions back so the model knows the coordinate space — any
        // tap_xy emitted after this should be in 0..width, 0..height.
        return ToolResult.TextWithImage(
            output = ok(buildJsonObject {
                put("description", "screenshot captured at native screen resolution")
                put("screen_width", shot.width)
                put("screen_height", shot.height)
                put(
                    "coordinate_space",
                    "Use tap_xy with x in 0..${shot.width} and y in 0..${shot.height}; " +
                        "coordinates are in the same pixel space as this image.",
                )
            }),
            jpeg = shot.jpeg,
        )
    }

    private fun activePackage(): String {
        val pkg = AxOps.activePackage()
        return ok(buildJsonObject { put("package", JsonPrimitive(pkg ?: "")) })
    }

    private suspend fun waitMs(args: Map<String, JsonElement>): String {
        val ms = args["ms"]?.jsonPrimitive?.intOrNull ?: 0
        delay(ms.coerceIn(0, 30_000).toLong())
        return ok()
    }

    // -- result helpers --------------------------------------------------

    private fun ok(extra: kotlinx.serialization.json.JsonObject? = null): String {
        val obj = buildJsonObject {
            put("status", "ok")
            extra?.forEach { (k, v) -> put(k, v) }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    private fun err(msg: String): String {
        val obj = buildJsonObject {
            put("status", "error")
            put("error", msg)
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    companion object {
        private const val TAG = "ToolDispatcher"
    }
}

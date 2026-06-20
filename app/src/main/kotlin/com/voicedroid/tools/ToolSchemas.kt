package com.voicedroid.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Function-tool schemas advertised to gpt-realtime-2. The names + parameter shapes
 * here must match the dispatcher in [ToolDispatcher].
 */
object ToolSchemas {

    val tools: JsonArray = buildJsonArray {
        // launch_app
        addJsonObject {
            put("type", "function")
            put("name", "launch_app")
            put("description",
                "Open or switch to an installed Android app by its display name. " +
                    "If the app is already running in another task, brings it to the " +
                    "foreground instead of starting a fresh instance. Use this for " +
                    "phrases like 'open X', 'launch X', 'switch to X', or 'go to X'. " +
                    "Fuzzy substring match across installed app labels.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                }
                putJsonArray("required") { add("name") }
            }
        }

        // close_app
        addJsonObject {
            put("type", "function")
            put("name", "close_app")
            put("description",
                "Close an installed Android app by its display name. Sends the app to " +
                    "the background (if foreground) and asks the system to kill its " +
                    "background processes. Use for phrases like 'close X', 'quit X', " +
                    "'exit X', 'stop X'. Aggressive OEM memory managers may not always " +
                    "honor the kill, but the app will be backgrounded either way.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("type", "string") }
                }
                putJsonArray("required") { add("name") }
            }
        }

        // tap_near_text
        addJsonObject {
            put("type", "function")
            put("name", "tap_near_text")
            put("description",
                "Tap a position offset from a visible text label. Use this whenever the " +
                    "target is an icon-only button (heart, like, repost, bookmark, share, " +
                    "profile picture, etc.) that sits NEAR a text element you can see. " +
                    "The text label gives us a precise anchor from the accessibility tree, " +
                    "and you supply the pixel offset from its center to your target. " +
                    "Example: in X/Twitter, the heart icon is roughly 60px to the LEFT " +
                    "of the like count text (e.g. '59.4K') — call tap_near_text with " +
                    "anchor_text='59.4K', dx=-60, dy=0. Positive dx is right, positive " +
                    "dy is down. ALWAYS prefer this over tap_xy when there's a nearby " +
                    "visible label, because tap_xy depends on you estimating absolute " +
                    "pixel coordinates which is unreliable for small icons.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("anchor_text") {
                        put("type", "string")
                        put("description", "A visible text or content-description on screen to anchor against")
                    }
                    putJsonObject("dx") {
                        put("type", "number")
                        put("description", "Horizontal offset in pixels from anchor center (negative = left)")
                    }
                    putJsonObject("dy") {
                        put("type", "number")
                        put("description", "Vertical offset in pixels from anchor center (negative = up)")
                    }
                }
                putJsonArray("required") { add("anchor_text"); add("dx"); add("dy") }
            }
        }

        // tap_text
        addJsonObject {
            put("type", "function")
            put("name", "tap_text")
            put("description",
                "Tap the on-screen element whose visible text or content description " +
                    "contains the given query (case-insensitive). Use this for buttons, " +
                    "list items, links, etc.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string") }
                }
                putJsonArray("required") { add("text") }
            }
        }

        // tap_xy
        addJsonObject {
            put("type", "function")
            put("name", "tap_xy")
            put("description", "Tap at absolute screen coordinates.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "number") }
                    putJsonObject("y") { put("type", "number") }
                }
                putJsonArray("required") { add("x"); add("y") }
            }
        }

        // scroll
        addJsonObject {
            put("type", "function")
            put("name", "scroll")
            put("description",
                "Scroll the active window in the given direction. " +
                    "'down' reveals content below, 'up' reveals content above.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("direction") {
                        put("type", "string")
                        putJsonArray("enum") { add("up"); add("down"); add("left"); add("right") }
                    }
                    putJsonObject("distance") {
                        put("type", "number")
                        put("description", "Fraction of viewport, 0..1 (default 0.6)")
                    }
                }
                putJsonArray("required") { add("direction") }
            }
        }

        // swipe
        addJsonObject {
            put("type", "function")
            put("name", "swipe")
            put("description", "Raw gesture: swipe from (x1,y1) to (x2,y2).")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("x1") { put("type", "number") }
                    putJsonObject("y1") { put("type", "number") }
                    putJsonObject("x2") { put("type", "number") }
                    putJsonObject("y2") { put("type", "number") }
                    putJsonObject("duration_ms") { put("type", "integer") }
                }
                putJsonArray("required") { add("x1"); add("y1"); add("x2"); add("y2") }
            }
        }

        // type
        addJsonObject {
            put("type", "function")
            put("name", "type")
            put("description",
                "Type text into the currently focused editable field. " +
                    "Fails if no field is focused.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string") }
                }
                putJsonArray("required") { add("text") }
            }
        }

        // key
        addJsonObject {
            put("type", "function")
            put("name", "key")
            put("description",
                "Press a system key: back, home, recents, notifications.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", "string")
                        putJsonArray("enum") {
                            add("back"); add("home"); add("recents"); add("notifications")
                        }
                    }
                }
                putJsonArray("required") { add("name") }
            }
        }

        // read_screen
        addJsonObject {
            put("type", "function")
            put("name", "read_screen")
            put("description",
                "Return the visible text on the current screen, as a single string. " +
                    "Use this to confirm what is showing or to answer questions about it.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }

        // dump_tree
        addJsonObject {
            put("type", "function")
            put("name", "dump_tree")
            put("description",
                "Return the accessibility tree of the current screen as structured text. " +
                    "Shows class names, view IDs, bounds (left,top-right,bottom), " +
                    "flags ([C]lickable, [F]ocusable, [E]ditable, [S]crollable), " +
                    "and text/content-description. Use this when you need precise element " +
                    "targeting, coordinates, or to understand the UI hierarchy.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max_depth") {
                        put("type", "integer")
                        put("description", "Max tree depth (default 15, max 30)")
                    }
                }
            }
        }

        // screenshot
        addJsonObject {
            put("type", "function")
            put("name", "screenshot")
            put("description",
                "Take a screenshot of the current screen. The captured image is added to " +
                    "the conversation so you can SEE it directly — colors, icons, layout, " +
                    "and anything without a text label. Call this whenever the user mentions " +
                    "a visual target (heart, like, repost, bookmark, profile picture, image, " +
                    "icon, button shape) or asks what's on screen. Do not refuse — you have " +
                    "vision capability via this tool. The tool result includes " +
                    "`screen_width` and `screen_height` — when you follow up with tap_xy, " +
                    "emit coordinates in that pixel space (the image you receive is at the " +
                    "phone's native resolution; tap_xy coordinates are the same).")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }

        // active_package
        addJsonObject {
            put("type", "function")
            put("name", "active_package")
            put("description", "Return the Android package name of the foreground app.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }

        // wait_ms
        addJsonObject {
            put("type", "function")
            put("name", "wait_ms")
            put("description",
                "Pause for the given number of milliseconds before the next tool call. " +
                    "Use sparingly between actions that need UI time to settle.")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ms") { put("type", "integer") }
                }
                putJsonArray("required") { add("ms") }
            }
        }
    }
}

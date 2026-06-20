# voice-droid — talk to your Android phone

Talk to your phone and it does things. Open apps, tap buttons, type, scroll, take
screenshots and reason about them, like a tweet, send a message. One sideloaded
APK, no PC needed, no cloud account beyond an OpenAI API key.

- **Brain:** OpenAI `gpt-realtime-2` (speech-to-speech + tool calling + vision) over WebRTC.
- **Hands:** Android `AccessibilityService` — taps, scrolls, gestures, screen reads, native screenshots.
- **Glue:** a foreground service that owns the mic, the speaker, the WebRTC peer connection, and a sideband WebSocket for large payloads (images).

Sister project to [voice-os](https://github.com/per-simmons/voice-os) (the Mac voice loop), with
the same tool-call pattern but Android-native and vision-capable.

---

## Build & install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 10+ (API 29). Targets API 34. Built with Kotlin 2.0 / Compose
Material 3 / AGP 8.7 / Gradle 8.14.

---

## First-run setup

Open Voice Droid. The **Permissions** card shows what's missing — grant each in
turn. The system flow involves a couple of separate prompts:

| Permission | Why | Where it lives |
|---|---|---|
| Microphone | listening | runtime prompt |
| Notifications | the foreground-service notification is the only thing that keeps Android from killing the session | runtime prompt (API 33+) |
| Accessibility service | the only way to actually tap, scroll, type, and read other apps' UI from the background; also enables on-demand screenshots | Settings → Accessibility → Voice Droid (toggle on) |
| Display over other apps | the floating PTT bubble | system overlay-permissions screen |

Then paste your **OpenAI API key** in the *OpenAI config* card. It's stored in
EncryptedSharedPreferences (AES-256-GCM, hardware-backed master key) and only
ever leaves the device when sent to `api.openai.com`.

Pick a **voice** in the same card (the realtime API supports `alloy`, `ash`,
`ballad`, `cedar`, `coral`, `echo`, `marin`, `sage`, `shimmer`, `verse`).
Default: `marin`.

---

## Modes

| Mode | What it does | Idle cost |
|---|---|---|
| **Off** | Service stopped. No mic, no network. | $0 |
| **Listening** | Mic streams continuously; server VAD detects turn end and the model auto-responds. Best for hands-free use. | $$$ — pays per second of audio that crosses VAD |
| **PTT** | Session stays connected but the mic is muted by default. A floating bubble overlay (drag to reposition) toggles listening on tap. Tap once to start, server VAD closes the turn, model responds, bubble auto-mutes. While the model speaks, just talk to **barge in** — no second tap. | ~$0 except per-press |

**Auto-off after 1 hour** (on by default, checkbox in the Mode card). Resets on
every mode change. Unchecking pops a warning that you'll be paying tokens
indefinitely.

**PTT bubble color legend:**
- dark grey = idle (mic muted)
- green core + white inner ring = listening (mic open)
- blue pulsing outer ring = model is speaking (still listening — talk to interrupt)

**Other PTT triggers** that also toggle Talk: the *Talk* button on the foreground-service notification, and the *play/pause* button on a Bluetooth headset.

---

## Tools the model has

| Tool | Args | What it does |
|---|---|---|
| `launch_app` | `name` | Fuzzy-match installed apps by display label, launch or switch-to-front via `PackageManager`. |
| `close_app` | `name` | Background the app (Home if foreground), `ActivityManager.killBackgroundProcesses`. |
| `tap_text` | `text` | Find first AX node whose text or content-description contains `text`, click it. |
| `tap_near_text` | `anchor_text`, `dx`, `dy` | Resolve anchor's exact AX-tree bounds, tap at `(center + dx, center + dy)` in screen pixels. **The reliable way to hit icon-only buttons sitting near a label** — e.g. the heart in X is ~60 px left of the like count. |
| `tap_xy` | `x`, `y` | Single-tap at absolute screen coordinates. Last-resort; LLM vision is imprecise on tall mobile screens. |
| `scroll` | `direction`, `distance?` | Swipe in the active window. `up`/`down`/`left`/`right`. |
| `swipe` | `x1, y1, x2, y2, duration_ms?` | Raw gesture. |
| `type` | `text` | Set text on the currently focused editable field. |
| `key` | `name` | System keys: `back` / `home` / `recents` / `notifications`. |
| `read_screen` | — | Newline-joined visible text from the AX tree. |
| `dump_tree` | `max_depth?` | AX tree as a flat indented dump — useful for the model when it needs to find non-obvious elements. |
| `screenshot` | — | Native-resolution JPEG of the screen. Sent to the model with `detail: high`. Tool result includes `screen_width` / `screen_height` for coordinate reference. |
| `active_package` | — | Foreground app's package name. |
| `wait_ms` | `ms` | Pause between actions for UI settle. |

**Tap priority** (in the default system prompt): own-text → `tap_text`; icon near
text → `tap_near_text`; only as last resort → `tap_xy`. Vision-language models
are good at "the heart is just left of the like count" but bad at "tap at
(540, 1574)" — `tap_near_text` uses the AX tree's exact label coords as the
anchor, eliminating the localization error.

---

## How it works

```
mic ──┐
      │   ┌───────────────────────────────────────────────────────────┐
      ├──▶│ VoiceDroidService (foreground, microphone + connected-dev)│
      │   │   • AudioRouter (BT > wired > speakerphone, MODE_IN_COMM) │
speaker◀──│   • WebRtcRealtimeClient                                   │
      │   │       PeerConnectionFactory → DataChannel (control)        │
      │   │       Sideband WebSocket via call_id (large payloads)      │
      │   │   • SessionLoop (mode FSM, VAD threshold, PTT silence)     │
      │   │   • PttBubble overlay  +  MediaButtonController (BT PTT)   │
      │   │   • DebugRecorder (last 10 records to /sdcard/Android/…)   │
      │   └───────────────────────────────────────────────────────────┘
      │                  │                ▲
      │           tool call               │ tool result (+ image)
      │                  ▼                │
      │   ┌───────────────────────────────────────────────────────────┐
      └──▶│ VoiceDroidAccessibilityService                            │
          │   AxOps: find/tap/swipe/type/read/screenshot              │
          └───────────────────────────────────────────────────────────┘
```

**WebRTC + sideband WebSocket.** Audio flows as Opus over RTP — low latency,
good quality. The DataChannel carries control events (`session.update`,
`response.create`, tool results) but has a ~64 KB per-message ceiling. So we
also open a **sideband WebSocket** to the same Realtime session using the
`call_id` returned in the `Location` header of the `/v1/realtime/calls` POST,
authed with the ephemeral key from `/v1/realtime/client_secrets`. The sideband
carries large messages — specifically, native-resolution screenshot bytes
inlined as `data:image/jpeg;base64,…` `input_image` content.

**One process, no IPC.** The Accessibility service publishes itself to a
static `instance` field on `onServiceConnected`; the foreground service
references it directly. Same process, zero Binder.

**Mode behaviour for Listening vs PTT.** The WebRTC local audio track is bound
to `AudioRecord` for the lifetime of the PeerConnection — `track.setEnabled(false)`
zeros the outbound frames but doesn't release the mic. So the OS mic indicator
stays on for any non-Off mode; **what matters is that no audio leaves the device
when listening is false.** Plus, server VAD with threshold ≥ 0.8 + the
`noise_reduction: far_field` filter mean even momentary noise doesn't reach
Whisper, so no phantom turns get committed.

**Reconnect.** Each transport reconnects independently. RealtimeClient (WebSocket
mode, unused by default) uses backoff and resends the last `session.update`.
WebRtcRealtimeClient rebuilds the PeerConnection + sideband WS on
failure / close; PTT mic state is remembered across reconnects.

---

## Settings overview

- **Permissions** — status of the four permissions above, with deep-link buttons to each.
- **OpenAI config** — API key, voice picker.
- **System prompts** — a built-in `Default` (read-only, clone to edit) plus user-created prompts. Pick the active one with a radio button. Edits push live to the running session via `session.update` (no teardown).
- **Mode** — Off / Listening / PTT, cumulative-token counter (persisted across sessions, with a Reset button), auto-off toggle.
- **Advanced** — server VAD threshold (default `0.9`, raise to 0.95 if background noise still triggers; lower if it misses quiet speech); PTT end-of-turn silence in ms (default 2500, PTT only).

---

## Debug recording

Every screenshot / tap / `tap_near_text` is saved to
`/sdcard/Android/data/com.voicedroid/files/debug/`. The recorder keeps the last
**10** records (auto-pruned).

Each record is a group of files sharing a prefix:
```
20260620-114830-512_7_tap_xy.jpg     ← screenshot at the moment of the tap, red crosshair at the tap point
20260620-114830-512_7_tap_xy.json    ← id, timestamp, dimensions, coords, success, active_package, user_said, model_said
20260620-114830-512_7_tap_text.tree.txt   ← (tap_text only) full AX-tree dump for diagnosing missing matches
latest.jpg                                ← convenience copy of the most recent screenshot
```

`tap_near_text` records also draw the **yellow anchor box** + a yellow line from
the anchor's center to the actual tap point, so you can eyeball the offset.

Pull a snapshot:
```bash
adb pull /sdcard/Android/data/com.voicedroid/files/debug/
```

The `user_said` / `model_said` fields in the JSON come from a singleton
`Transcripts` buffer that watches `response.output_audio_transcript.delta` and
`conversation.item.input_audio_transcription.completed` — so you can correlate
intent ("like that post") with action ("tap_xy at (540, 1030)").

---

## Dependencies (all Apache-2.0 / MIT)

| Lib | Why |
|---|---|
| Stream WebRTC (Android fork) 1.3 | WebRTC peer connection. |
| OkHttp 4.12 | REST POST for SDP exchange + sideband WebSocket + (unused) WS transport. |
| kotlinx.serialization 1.7.3 | Tool args, prompts, JSON over the wire. |
| ONNX Runtime Android 1.17 | Silero VAD (client-side pre-filter; only used in the WebSocket transport path which is currently off by default). |
| androidx.security 1.1.0-alpha06 | EncryptedSharedPreferences for the API key. |
| androidx.compose Material 3 | Settings UI. |
| androidx.lifecycle.compose | Permission rechecks on `ON_RESUME`. |
| kotlinx.coroutines 1.9 | Service-wide concurrency. |

No Picovoice, no DroidRun (GPL), no per-app integrations.

---

## Cost & privacy

- All audio + screenshots are sent only to `api.openai.com`. Nothing else leaves the phone.
- API key encrypted at rest with the AndroidX master key. Never written to logs.
- Off and PTT idle are ≈$0. PTT-per-turn cost depends on how long you talk and the response length. Listening mode is on the order of $1/hour with the default `gpt-realtime-2` pricing — set a hard cap with the **auto-off** checkbox.
- Cumulative token counter in the Mode card sticks across sessions, mode toggles, and reinstalls so you can sanity-check spending.

---

## Known limits

- **LLM pixel coords are noisy.** GPT-4V family vision encoders downsample and tile the image — the model is great at "*what* is here" but poor at "exactly *where* in pixels." `tap_near_text` exists exactly to sidestep this by using AX-tree bounds as the anchor. For apps where neither an own-label nor a nearby anchor is present, `tap_xy` will be hit-or-miss.
- **X / Twitter actively hides individual action buttons from the AX tree.** Heart / repost / reply etc. are rendered as a single non-clickable composite node with a giant content-description string. You can find text *counts* like "59.4K", so `tap_near_text` with offsets works — but `tap_text "Like"` won't. This is intentional on X's part, not our bug.
- **Samsung One UI silently doesn't deliver `onKeyEvent` to filter-key-events services.** We don't rely on volume keys for PTT for that reason — the floating bubble is the primary trigger.
- **The OS mic indicator stays on for any non-Off mode.** That's because WebRTC's `JavaAudioDeviceModule` holds the `AudioRecord` for the session's lifetime. The data is gated (`track.setEnabled(false)` zeros frames when listening is false) so nothing leaves the device, but the visual indicator can't be lied to — and shouldn't be.

---

## Project layout

```
app/src/main/kotlin/com/voicedroid/
├── MainActivity.kt                 — Compose host; snaps mode to Off on cold start
├── accessibility/
│   ├── VoiceDroidAccessibilityService.kt
│   └── AxOps.kt                    — find/tap/swipe/type/read/screenshot
├── service/
│   ├── VoiceDroidService.kt        — foreground service + mode FSM + companions
│   ├── SessionLoop.kt              — voice loop (mode, listening, speaking, tools)
│   ├── AudioRouter.kt              — BT > wired > speakerphone
│   ├── PttBubble.kt                — draggable overlay + animation
│   ├── MediaButtonController.kt    — BT play/pause → toggleTalk
│   ├── DebugRecorder.kt            — saves last 10 taps/screenshots
│   ├── Transcripts.kt              — live HEARD + model reply buffer
│   └── realtime/
│       ├── SessionConfig.kt        — session.update JSON builder
│       ├── RealtimeClient.kt       — OkHttp WebSocket transport (unused by default)
│       └── WebRtcRealtimeClient.kt — Stream WebRTC + sideband WS
├── tools/
│   ├── ToolSchemas.kt              — function declarations advertised to the model
│   ├── ToolDispatcher.kt           — routes model tool calls to AxOps / Launcher
│   └── Launcher.kt                 — PackageManager-based launch / close
├── storage/
│   ├── Settings.kt                 — SharedPreferences (mode, voice, VAD, etc.)
│   ├── SecureStore.kt              — EncryptedSharedPreferences (API key)
│   └── Prompts.kt                  — SystemPrompt manager
├── ui/SettingsScreen.kt            — Compose Material 3 settings
└── util/Permissions.kt
```

---

## License

Apache-2.0.

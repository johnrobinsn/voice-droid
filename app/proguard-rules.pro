# Keep our Service + AccessibilityService entry points
-keep class com.voicedroid.service.** { *; }
-keep class com.voicedroid.accessibility.** { *; }

# WebRTC (Stream fork) — native JNI + observer callbacks
-keep class org.webrtc.** { *; }

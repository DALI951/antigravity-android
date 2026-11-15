# Antigravity Android ProGuard rules
# Keep Kotlinx Serialization models
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt

# Keep OkHttp & WebSocket rules if needed
-dontwarn okhttp3.**
-dontwarn okio.**

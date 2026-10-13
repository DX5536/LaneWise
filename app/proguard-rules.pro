# MapLibre ships its own consumer rules; keep JNI-facing classes just in case.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# kotlinx.serialization (we only use the JsonElement tree API, but keep metadata safe)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

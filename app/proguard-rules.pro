# ── Lingji v2.0 ProGuard ──

# Keep Room entities
-keep class com.myagent.app.memory.** { *; }

# General
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Remove verbose logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
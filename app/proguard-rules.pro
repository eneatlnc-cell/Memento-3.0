# ── Memento v2.0 ProGuard ──

# Keep Room entities
-keep class com.myagent.app.memory.** { *; }

# C-B1 修复：JNI 通过方法名反射调用 TokenCallback.onToken，
# R8 重命名会导致 GetMethodID 返回 null → Release 包 token 流为空。
# LlamaNative 含 native 方法已被默认规则保护，但 TokenCallback 无 native 成员需显式 keep。
-keep class com.myagent.app.model.LlamaNative$TokenCallback { *; }
-keep class com.myagent.app.model.LlamaNative { *; }

# General
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Remove verbose logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
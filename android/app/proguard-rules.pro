# ProGuard rules for EyeMonitor
# Keep Gson model classes
-keep class com.eyemonitor.model.** { *; }

# Keep OkHttp WebSocket
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep AMap 3D SDK
-dontwarn com.amap.api.**
-keep class com.amap.api.** { *; }
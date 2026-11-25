# Keep OkHttp and Android service entry points intact
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.jarvis.assistant.services.** { *; }

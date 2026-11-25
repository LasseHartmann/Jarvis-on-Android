# OkHttp & Okio optional platform classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep Android service and assist overlay entry points intact
-keep class com.jarvis.assistant.services.** { *; }
-keep class com.jarvis.assistant.assist.JarvisAssistActivity { *; }
-keep class com.jarvis.assistant.JarvisApplication { *; }

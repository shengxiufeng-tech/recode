# FFmpegKit calls back into Java from native code (log/statistics/session callbacks).
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
# Deliberately no -dontwarn for com.arthenica: if a dependency class goes missing again,
# R8 must fail the build instead of producing an APK that crashes at startup.

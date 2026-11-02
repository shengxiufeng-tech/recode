# FFmpegKit calls back into Java from native code (log/statistics/session callbacks).
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**

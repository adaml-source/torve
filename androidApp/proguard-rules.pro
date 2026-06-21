# --------------------------------------------------------------
# Torve ProGuard / R8 Rules
# --------------------------------------------------------------

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.torve.**$$serializer { *; }
-keepclassmembers class com.torve.** {
    *** Companion;
}
-keepclasseswithmembers class com.torve.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keep class io.ktor.client.engine.** { *; }

# SQLDelight
-keep class com.torve.db.** { *; }
-keep class app.cash.sqldelight.** { *; }

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Google Cast
-keep class com.google.android.gms.cast.** { *; }
-dontwarn com.google.android.gms.cast.**

# Coil
-keep class coil3.** { *; }
-dontwarn coil3.**

# Torve serializers: keep generated serializer entry points above, but do
# not keep the full com.torve package. Release builds should still shrink
# repositories, view models, resolver code, and DTO implementation names.
# Keep only explicit @SerialName member names used by debrid payloads.
-keepclassmembernames class com.torve.data.debrid.** {
    @kotlinx.serialization.SerialName *;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Amazon IAP runtime entry points.
-keep class com.amazon.device.iap.** { *; }
-keep class com.amazon.a.** { *; }
-dontwarn com.amazon.device.iap.**

# General
-keepattributes Signature
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MPV JNI bridge: libplayer.so resolves these callback entry points by exact
# class and method signature, so release shrinking must not rename or strip them.
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$EventObserver { *; }
-keep class is.xyz.mpv.MPVLib$LogObserver { *; }

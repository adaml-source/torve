# ──────────────────────────────────────────────────────────────
# Torve (StreamVault) ProGuard / R8 Rules
# ──────────────────────────────────────────────────────────────

# ── Kotlinx Serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.streamvault.**$$serializer { *; }
-keepclassmembers class com.streamvault.** {
    *** Companion;
}
-keepclasseswithmembers class com.streamvault.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable data classes
-keep @kotlinx.serialization.Serializable class com.streamvault.** { *; }

# ── Ktor ──
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keep class io.ktor.client.engine.** { *; }

# ── SQLDelight ──
-keep class com.streamvault.db.** { *; }
-keep class app.cash.sqldelight.** { *; }

# ── Koin ──
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ── ExoPlayer / Media3 ──
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── Google Cast ──
-keep class com.google.android.gms.cast.** { *; }
-dontwarn com.google.android.gms.cast.**

# ── Coil ──
-keep class coil3.** { *; }
-dontwarn coil3.**

# ── Domain models (used via reflection in serialization) ──
-keep class com.streamvault.domain.model.** { *; }
-keep class com.streamvault.data.debrid.** { *; }
-keep class com.streamvault.data.trakt.** { *; }
-keep class com.streamvault.data.addon.StremioModels** { *; }
-keep class com.streamvault.data.metadata.TmdbModels** { *; }

# ── Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── General ──
-keepattributes Signature
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

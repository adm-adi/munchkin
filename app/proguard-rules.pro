# Munchkin rules for ProGuard
-keepattributes *Annotation*
-keepattributes Signature

# Ktor
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.munchkin.app.**$$serializer { *; }
-keepclassmembers class com.munchkin.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.munchkin.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Netty
-dontwarn io.netty.**
-keep class io.netty.** { *; }

# SLF4J
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# OkHttp (for GitHub API calls)
-dontwarn okhttp3.**
-dontwarn okio.**

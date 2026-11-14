# Munchkin R8 / ProGuard rules
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Ktor CIO
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# kotlinx.serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.munchkin.app.**$$serializer { *; }
-keepclassmembers class com.munchkin.app.** {
    *** Companion;
    static *** INSTANCE;
}
-keepclasseswithmembers class com.munchkin.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum com.munchkin.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class com.munchkin.app.** { *; }
-dontwarn androidx.room.paging.**

# ML Kit / ZXing
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.zxing.**

# Strip verbose, debug, and info logging calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

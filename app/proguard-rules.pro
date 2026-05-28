# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.json.** { *; }
-keep class * implements kotlinx.serialization.KSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Koin
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Timber
-dontwarn timber.log.**

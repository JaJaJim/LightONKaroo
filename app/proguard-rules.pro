# Keep Karoo SDK classes
-keep class io.hammerhead.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}
-keep class kotlinx.serialization.json.** { *; }
-keep class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}

# Keep our data classes
-keep class io.github.JaJaJim.lightonkaroo.data.** { *; }
-keep class io.github.JaJaJim.lightonkaroo.engine.DisplayInfo { *; }

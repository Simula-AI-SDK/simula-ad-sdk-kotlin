# Keep kotlinx.serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep SDK model classes
-keep class com.simula.ad.sdk.model.** { *; }
-keep class com.simula.ad.sdk.network.** { *; }

# IAB Open Measurement SDK (OMID). The OMID JS service bridges to these classes
# by stable names, so keep them and silence the optional Amazon attestation refs
# (com.amazon.privacypass) that ship unresolved in the jar.
-keep class com.iab.omid.library.simulaad.** { *; }
-dontwarn com.amazon.**

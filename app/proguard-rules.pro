# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.tongxie.copilotgo.**$$serializer { *; }
-keepclassmembers class com.tongxie.copilotgo.** {
    *** Companion;
}
-keepclasseswithmembers class com.tongxie.copilotgo.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

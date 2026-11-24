# Keep JNI entry points — called from C++ through reflection-style lookup.
-keepclasseswithmembernames class com.tibatech.gptmax.bridge.LlamaBridge {
    native <methods>;
}
-keep class com.tibatech.gptmax.bridge.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.tibatech.gptmax.**$$serializer { *; }
-keepclassmembers class com.tibatech.gptmax.** {
    *** Companion;
}

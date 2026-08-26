# AAP Test Host Proguard & R8 Rules

# Preserve all native methods across the host application
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve AapAudioPlayer and its companion object for JNI interop
-keep class org.androidaudioplugin.host.core.AapAudioPlayer { *; }
-keep class org.androidaudioplugin.host.core.AapAudioPlayer$* { *; }

# Preserve AAP Core Hosting and IPC structures
-keep class org.androidaudioplugin.** { *; }
-keep interface org.androidaudioplugin.** { *; }

# Preserve ktmidi UMP structures used in audio player
-keep class dev.atsushieno.ktmidi.** { *; }

# Preserve Oboe audio callback interfaces and helpers
-keep class com.google.oboe.** { *; }

# Keep attributes required for reflection, line numbers and annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

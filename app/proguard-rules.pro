# R8 / ProGuard - DTX Mixer (com.kendang.realpads). Dipakai bila isMinifyEnabled = true.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# JNI: native-lib.cpp mengekspor Java_com_kendang_realpads_AudioEngine_<nama> -> jangan di-rename.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.kendang.realpads.AudioEngine { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

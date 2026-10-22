# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# WorkManager's Room database is created by reflection (WorkDatabase_Impl); without this the
# release build crashes on start ("Failed to create an instance of class androidx.work.impl.WorkDatabase")
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# Same for WorkManager's input mergers: its own "-keep class" rule doesn't keep the constructor in
# R8 full mode, and work never starts ("Could not create Input Merger ...OverwritingInputMerger")
-keep class * extends androidx.work.InputMerger { <init>(); }

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
# Keep all model classes used by Firebase Realtime Database reflection
-keep class com.marekguran.unitrack.data.model.** { *; }

# Keep inner model classes used by Firebase (e.g. StudentDbModel)
-keepclassmembers class com.marekguran.unitrack.** {
    static final % *;
    <init>(...);
}
-keep class com.marekguran.unitrack.**$StudentDbModel { *; }

# Firebase Auth
-keep class com.google.firebase.auth.** { *; }
-dontwarn com.google.firebase.auth.**
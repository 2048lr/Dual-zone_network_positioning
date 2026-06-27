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

# predict4java and dependencies
-keep class com.github.amsacode.predict4java.** { *; }
-keep class org.apache.commons.lang.** { *; }
-keep class com.github.davidmoten.guavamini.** { *; }
-keep class org.apache.commons.logging.** { *; }
-keep class org.apache.commons.logging.impl.** { *; }
-dontwarn org.apache.commons.logging.**

# 自定义 commons-logging 工厂（通过 SPI 注册，类名必须保留）
-keep class com.example.radioarealocator.logging.** { *; }
-keep class com.example.radioarealocator.logging.AndroidLogFactory { *; }
-keep class com.example.radioarealocator.logging.AndroidLog { *; }

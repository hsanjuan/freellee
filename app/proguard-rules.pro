# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Health Connect classes
-keep class androidx.health.connect.client.** { *; }
-keep interface androidx.health.connect.client.** { *; }

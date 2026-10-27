# Tesseract's Java wrapper is called from native code.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep readable stack traces in the private problem log.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ZXing: format readers are created by name in some paths
-keep class com.google.zxing.** { *; }

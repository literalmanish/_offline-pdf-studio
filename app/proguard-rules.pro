# Proguard rules for Offline PDF Studio

# PDFBox-Android rules
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**

# Compose rules
-keep class androidx.compose.** { *; }

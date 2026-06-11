# ── Glide ────────────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ── Gson (used for hidden-months backup JSON) ─────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── App data classes serialised by Gson / passed via Intent ──────────────────
# NOTE: package is com.anant.mediacurator — keeping these preserves field names so
# Gson backup JSON stays compatible across releases (renamed fields = broken restore).
-keep class com.anant.mediacurator.MediaItem    { *; }
-keep class com.anant.mediacurator.MediaStats   { *; }
-keep class com.anant.mediacurator.MonthGroup   { *; }
-keep class com.anant.mediacurator.MediaType    { *; }
-keep class com.anant.mediacurator.SortMode     { *; }

# ── PdfBox-Android (tom_roush) ───────────────────────────────────────────────
# Optional JPEG2000 decoder that PdfBox references but does not bundle — suppress.
-dontwarn com.gemalto.jp2.JP2Decoder
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ── PhotoView (chrisbanes) ────────────────────────────────────────────────────
-keep class com.github.chrisbanes.photoview.** { *; }

# ── AndroidX / Lifecycle ─────────────────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ── Keep line numbers for crash reports ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

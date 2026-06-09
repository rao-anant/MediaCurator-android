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
-keep class com.mediacurator.MediaItem     { *; }
-keep class com.mediacurator.MonthGroup    { *; }
-keep class com.mediacurator.MediaType     { *; }
-keep class com.mediacurator.SortMode      { *; }

# ── PhotoView (chrisbanes) ────────────────────────────────────────────────────
-keep class com.github.chrisbanes.photoview.** { *; }

# ── AndroidX / Lifecycle ─────────────────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ── Keep line numbers for crash reports ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

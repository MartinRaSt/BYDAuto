# =====================================================================
# BYD Logger – ProGuard / R8 pravidla
# =====================================================================

# --- Room (entita, DAO, databáze) ---
# Room generuje kód z anotací; data class fields musejí zůstat přístupné
-keep class com.byd.charging.data.** { *; }
-keepclassmembers class com.byd.charging.data.** { *; }

# Room interní třídy
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# --- MPAndroidChart ---
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- ViewBinding (generované třídy) ---
-keep class com.byd.charging.databinding.** { *; }

# --- Kotlin coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# --- Obecná pravidla pro Android komponenty ---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.lifecycle.ViewModel

# --- Zachovat názvy pro ladění stacktrace ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Zachovat anotace (Room, Kotlin metadata) ---
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

# --- Kotlin metadata (nutné pro reflection v Room a Kotlin) ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontnote kotlin.**

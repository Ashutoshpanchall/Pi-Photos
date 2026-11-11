# Proguard / R8 rules for PiPhotos

# Keep Android and ViewBinding classes
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
}

# Keep Kotlin reflection & data classes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Room entities when added
-keepclassmembers class * {
    @androidx.room.* <fields>;
}


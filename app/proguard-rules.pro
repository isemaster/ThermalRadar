# TERMO1 ProGuard rules

# Keep model classes for serialization/reflection
-keep class com.termo1.radar.model.** { *; }

# Keep sensor-related classes
-keep class com.termo1.radar.ui.RadarRenderer { *; }
-keep class com.termo1.radar.ui.UiManager { *; }
-keep class com.termo1.radar.ui.SettingsActivity { *; }

# Keep Android system classes we use via reflection
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
